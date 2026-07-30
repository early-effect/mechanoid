package mechanoid.docs

import specular.ziotest.DocSpecSuite

/** JVM test discovery for the Interactive DocSpec (shared with docsJS ClientMain). */
object InteractiveSuite extends DocSpecSuite:
  def doc = Interactive.doc
