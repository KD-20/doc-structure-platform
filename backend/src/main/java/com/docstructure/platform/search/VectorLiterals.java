package com.docstructure.platform.search;

/** Shared pgvector text-literal formatting, e.g. "[0.1,0.2,...]" — used by both the write path (ExtractionService) and the read path (SearchService). */
public final class VectorLiterals {

    private VectorLiterals() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
