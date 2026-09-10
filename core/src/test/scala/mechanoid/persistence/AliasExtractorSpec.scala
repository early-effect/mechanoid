package mechanoid.persistence

import zio.*
import zio.test.*
import mechanoid.*

object AliasExtractorSpec extends ZIOSpecDefault:

  enum InitiativeState derives Finite:
    case Draft
    case Live(
        @alias("campaign") campaignIds: List[Long],
        @alias templateId: String,
        @alias maybeTemplate: Option[Int],
    )

  enum PlainState derives Finite:
    case Idle, Running

  case class CampaignId(raw: String)
  object CampaignId:
    given AliasCodec[CampaignId] = _.raw

  case class Tagged(@alias("campaign") id: CampaignId)

  def spec = suite("AliasExtractor.derived")(
    test("empty states and unannotated types yield no aliases") {
      val fromDraft = AliasExtractor.derived[InitiativeState].aliases(InitiativeState.Draft)
      val fromPlain = AliasExtractor.derived[PlainState].aliases(PlainState.Idle)
      assertTrue(fromDraft.isEmpty, fromPlain.isEmpty)
    },
    test("scalar, list, and option fields become aliases") {
      val state = InitiativeState.Live(List(1L, 2L), "t-9", Some(7))
      val got   = AliasExtractor.derived[InitiativeState].aliases(state).toSet
      assertTrue(
        got == Set(
          Alias("campaign", "1"),
          Alias("campaign", "2"),
          Alias("templateId", "t-9"),
          Alias("maybeTemplate", "7"),
        )
      )
    },
    test("None option contributes nothing") {
      val state = InitiativeState.Live(Nil, "t-9", None)
      val got   = AliasExtractor.derived[InitiativeState].aliases(state).toSet
      assertTrue(got == Set(Alias("templateId", "t-9")))
    },
    test("custom AliasCodec is used for annotated fields") {
      val got = AliasExtractor.derived[Tagged].aliases(Tagged(CampaignId("camp-1")))
      assertTrue(got == Chunk(Alias("campaign", "camp-1")))
    },
  )
end AliasExtractorSpec
