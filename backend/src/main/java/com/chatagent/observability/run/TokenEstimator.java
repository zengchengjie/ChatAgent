package com.chatagent.observability.run;

public final class TokenEstimator {

    private TokenEstimator() {}

    /**
     * Extremely rough estimator used only when provider doesn't return usage.
     *
     * <p>For CJK-heavy content this tends to overestimate; for code it may underestimate. That's OK as
     * long as we tag metrics as estimated.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }
}

