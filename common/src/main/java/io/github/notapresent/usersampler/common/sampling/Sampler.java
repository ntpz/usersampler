package io.github.notapresent.usersampler.common.sampling;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import io.github.notapresent.usersampler.common.HTTP.*;
import io.github.notapresent.usersampler.common.site.FatalSiteError;
import io.github.notapresent.usersampler.common.site.RetryableSiteError;
import io.github.notapresent.usersampler.common.site.SiteAdapter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;


public class Sampler {
    public static final int MAX_BATCH_RETRIES = 2;
    private final Set<SiteAdapter> inProgress = new HashSet<>();
    private final List<Sample> results = new ArrayList<>();
    private final RequestMultiplexer muxer;
    private final RequestFactory requestFactory;

    @Inject
    public Sampler(RequestMultiplexer muxer, RequestFactory requestFactory,
                   @Named("utcNow") LocalDateTime startedAt) {
        this.muxer = muxer;
        this.requestFactory = requestFactory;
    }

    public List<Sample> takeSamples(List<SiteAdapter> adapters) {
        for (SiteAdapter site : adapters) {
            site.reset();
            inProgress.add(site);
        }

        while (!inProgress.isEmpty()) {
            processBatch(makeBatch(inProgress));
        }

        return results;
    }

    private RequestBatch makeBatch(Collection<SiteAdapter> sites) {
        RequestBatch batch = new RequestBatch();

        for (SiteAdapter site : sites) {
            site.getRequests(requestFactory).forEach((req) -> batch.put(req, site));
        }

        return batch;
    }

    private void processBatch(RequestBatch batch) {
        int batchRetries = 0;

        while (batchRetries++ < MAX_BATCH_RETRIES && !batch.isEmpty()) {
            RequestBatch retryBatch = new RequestBatch();
            Map<Request, Future<Response>> responseFutures = muxer.multiSend(batch.requests());

            for (Request request : responseFutures.keySet()) {
                SiteAdapter site = batch.siteFor(request);

                Future<Response> responseFuture = responseFutures.get(request);

                if (processResponseFuture(site, responseFuture)) {
                    retryBatch.put(request, site);
                }
            }

            batch = retryBatch;
        }

        for (SiteAdapter site : batch.sites()) {
            inProgress.remove(site);
            String failedRequests = String.join("\n",
                    batch.requestsForSite(site)
                            .stream()
                            .map(Request::toString)
                            .collect(Collectors.toList()));

            String message = String.format(
                    "Failed to fetch after %d retries:%n%n%s",
                    batchRetries,
                    failedRequests
            );
            results.add(makeSample(site, message));
        }
    }

    private boolean processResponseFuture(SiteAdapter site, Future<Response> respFut) {
        try {
            Response response = respFut.get();
            site.registerResponse(response);

            if (site.isDone()) {
                inProgress.remove(site);
                results.add(makeSample(site));
            }
            return false;
        } catch (HTTPError | FatalSiteError e) {    // Failed request considered a fatal error
            inProgress.remove(site);
            results.add(makeSample(site, e.getMessage()));
            return false;
        } catch (RetryableSiteError e) {
            return true;
        } catch (InterruptedException | ExecutionException e) {   // Should never happen
            throw new RuntimeException(e);
        }
    }

    private Sample makeSample(SiteAdapter site) {
        return new Sample(site, site.getResult());
    }

    private Sample makeSample(SiteAdapter site, String errorMessage) {
        return new Sample(site, errorMessage);
    }
}
