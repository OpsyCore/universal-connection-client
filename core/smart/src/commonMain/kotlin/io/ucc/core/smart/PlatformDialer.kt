package io.ucc.core.smart

/** Per-platform default socket dialer (see [TcpConnectionTester.Dialer]). */
internal expect fun platformDialer(): TcpConnectionTester.Dialer
