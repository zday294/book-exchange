package com.bookexchange.exchange;

/** Produces candidate exchange codes. Uniqueness is the caller's job (check + unique index). */
public interface ExchangeCodeGenerator {

    String next();
}
