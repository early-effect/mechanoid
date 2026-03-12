package mechanoid.machine

import zio.test.*
import mechanoid.core.Finite

object CompileTimeRejectSpec extends ZIOSpecDefault:

  enum S derives Finite:
    case A, B, C

  enum E derives Finite:
    case E1, E2, E3

  def spec = suite("Compile-time duplicate rejection")(
    test("direct duplicate in assembly() is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B, S.A via E.E1 to S.C)
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate in assemblyAll is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assemblyAll[S, E]:
          S.A via E.E1 to S.B
          S.A via E.E1 to S.C
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate through ++ composition is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B) ++
          assembly[S, E](S.A via E.E1 to S.C)
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate through combine() is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        combine(
          assembly[S, E](S.A via E.E1 to S.B),
          assembly[S, E](S.A via E.E1 to S.C),
        )
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate through ++ is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B) ++
          assembly[S, E](S.A via E.E1 to S.C)
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate through Machine(assembly(...)) is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        Machine(assembly[S, E](S.A via E.E1 to S.B, S.A via E.E1 to S.C))
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("override resolves duplicate in assembly()") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](
          S.A via E.E1 to S.B,
          (S.A via E.E1 to S.C) @@ Aspect.overriding,
        )
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("override resolves duplicate in combine()") {
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
    test("override resolves duplicate in ++") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B) ++
          assembly[S, E]((S.A via E.E1 to S.C) @@ Aspect.overriding)
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("no duplicates compiles successfully") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B, S.B via E.E2 to S.C)
      """)
      assertZIO(result)(Assertion.isRight)
    },
  )

end CompileTimeRejectSpec
