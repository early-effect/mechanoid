package mechanoid.persistence.postgres

import saferis.*
import zio.*

/** Run a fragment on this session. A call outside `transact` checks out one connection. */
private[postgres] object SessionSyntax:
  extension (session: SqlSession)
    def run[A](effect: ZIO[SqlSession, SaferisError, A]): IO[SaferisError, A] =
      effect.provideEnvironment(ZEnvironment(session))
