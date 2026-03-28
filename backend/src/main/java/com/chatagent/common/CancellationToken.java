package com.chatagent.common;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;

public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new ApiException(HttpStatus.REQUEST_TIMEOUT, "Request cancelled");
        }
    }
}

