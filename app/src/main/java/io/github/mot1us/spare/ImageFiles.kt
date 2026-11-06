package io.github.mot1us.spare

interface ImageFiles { fun bytes(url: String, fetch: () -> ByteArray): ByteArray }
