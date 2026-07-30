package mechanoid.docs

import mechanoid.core.MechanoidError
import zio.*

/** Specular `exampleZIO` requires `E = Nothing`; Mechanoid errors are not `Throwable`. */
object DocZIO:
  extension [R, A](zio: ZIO[R, MechanoidError, A])
    def asDoc: URIO[R, A] =
      zio.foldZIO(e => ZIO.dieMessage(e.toString), ZIO.succeed)
