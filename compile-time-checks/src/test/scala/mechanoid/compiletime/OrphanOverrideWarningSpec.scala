package mechanoid.compiletime

import zio.test.*

/** Compile-time tests for orphan override warnings.
  *
  * An "orphan override" is a transition marked with `@@ Aspect.overriding` that doesn't actually override anything (no
  * duplicate exists). This is likely a mistake, so the compiler emits a warning.
  *
  * Because this module uses `-Werror`, these warnings become errors that `typeCheck` can detect.
  */
object OrphanOverrideWarningSpec extends ZIOSpecDefault:

  def spec = suite("Orphan override warnings")(
    // Note: Orphan override warnings are only emitted when Machine.apply can inspect
    // the assembly at compile time. This requires using `inline def` to pass the assembly.
    // Plain `assembly[S, E](...)` without Machine doesn't emit warnings because
    // the orphan is only detected when Machine.apply macro runs.
    test("orphan override via inline def emits warning") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        inline def orphanAssembly = assembly[S, E]((S.A via E.E1 to S.B) @@ Aspect.overriding)
        Machine(orphanAssembly)
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("multiple orphan overrides via inline def emits warnings") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        inline def orphans = assembly[S, E](
          (S.A via E.E1 to S.B) @@ Aspect.overriding,
          (S.B via E.E2 to S.C) @@ Aspect.overriding,
        )
        Machine(orphans)
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("non-orphan override (resolves duplicate) does NOT warn") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        // This override actually overrides a duplicate - NOT orphan
        assembly[S, E](S.A via E.E1 to S.B) ++
          assembly[S, E]((S.A via E.E1 to S.C) @@ Aspect.overriding)
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("non-orphan override in combine does NOT warn") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        combine(
          assembly[S, E](S.A via E.E1 to S.B),
          assembly[S, E]((S.A via E.E1 to S.C) @@ Aspect.overriding),
        )
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("non-orphan override in Machine(combine(...)) does NOT warn") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        Machine(combine(
          assembly[S, E](S.A via E.E1 to S.B),
          assembly[S, E]((S.A via E.E1 to S.C) @@ Aspect.overriding),
        ))
      """)
      assertZIO(result)(Assertion.isRight)
    },
  )

end OrphanOverrideWarningSpec
