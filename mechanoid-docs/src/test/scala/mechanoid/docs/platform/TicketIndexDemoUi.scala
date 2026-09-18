package mechanoid.docs.platform
// live ticket inbox panel

import ascent.*
import ascent.ast.Attr
import ascent.dsl.*
import ascent.squawk.Squawk
import ascent.domtypes.{AttrKey, Codec}
import mechanoid.*
import mechanoid.persistence.*
import zio.*
import zio.json.*

import scala.language.implicitConversions

sealed trait IndexDemoTicket derives Finite, JsonCodec
sealed trait IndexDemoActive extends IndexDemoTicket derives Finite, JsonCodec

final case class IndexDemoOpen(
    @alias number: String,
    @index assignee: String,
    @index project: String,
    @indexRank priority: Int,
) extends IndexDemoActive

final case class IndexDemoInProgress(
    @alias number: String,
    @index assignee: String,
    @index project: String,
    @indexRank priority: Int,
) extends IndexDemoActive

final case class IndexDemoArchived(
    @alias number: String,
    @index assignee: String,
    @index project: String,
    @indexRank priority: Int,
) extends IndexDemoTicket

enum IndexDemoEvent derives Finite, JsonCodec:
  case Start, Archive, Reopen

/** Live ticket index panel: every public alias / index / query surface is a click. */
object TicketIndexDemoUi:

  type Ticket      = IndexDemoTicket
  type Active      = IndexDemoActive
  type Open        = IndexDemoOpen
  type InProgress  = IndexDemoInProgress
  type Archived    = IndexDemoArchived
  type TicketEvent = IndexDemoEvent

  val Open: IndexDemoOpen.type             = IndexDemoOpen
  val InProgress: IndexDemoInProgress.type = IndexDemoInProgress
  val Archived: IndexDemoArchived.type     = IndexDemoArchived
  val Start: IndexDemoEvent.Start.type     = IndexDemoEvent.Start
  val Archive: IndexDemoEvent.Archive.type = IndexDemoEvent.Archive
  val Reopen: IndexDemoEvent.Reopen.type   = IndexDemoEvent.Reopen

  val Me      = "me"
  val Other   = "other"
  val Alpha   = "alpha"
  val Beta    = "beta"
  val PageLen = 3

  val machine: Machine[Ticket, TicketEvent] = Machine(
    assembly[Ticket, TicketEvent](
      (state[IndexDemoOpen] via Start).to[IndexDemoInProgress] { (s, _) =>
        IndexDemoInProgress(s.number, s.assignee, s.project, s.priority)
      },
      (state[IndexDemoOpen] via Archive).to[IndexDemoArchived] { (s, _) =>
        IndexDemoArchived(s.number, s.assignee, s.project, s.priority)
      },
      (state[IndexDemoInProgress] via Archive).to[IndexDemoArchived] { (s, _) =>
        IndexDemoArchived(s.number, s.assignee, s.project, s.priority)
      },
      (state[IndexDemoArchived] via Reopen).to[IndexDemoOpen] { (s, _) =>
        IndexDemoOpen(s.number, s.assignee, s.project, s.priority)
      },
    )
  )

  val aliases: AliasExtractor[IndexDemoTicket] = AliasExtractor.derived[IndexDemoTicket]
  val indexes: IndexExtractor[IndexDemoTicket] = IndexExtractor.derived[IndexDemoTicket]

  enum Chip:
    case All, ArchivedOnly, ActiveOnly, NotArchived

  enum SortPick:
    case Edited, Rank

  def queryOf(
      assignee: String,
      chip: Chip,
      project: Option[String],
      sort: SortPick,
      rankMin: Option[Long],
      startAfter: Option[IndexCursor[String]],
      limit: Int = PageLen,
  ): IndexQueryBuilder[Ticket] =
    val base     = IndexQuery.of[IndexDemoTicket].assignee(assignee)
    val filtered = chip match
      case Chip.All          => base
      case Chip.ArchivedOnly => base.only(state[IndexDemoArchived])
      case Chip.ActiveOnly   => base.only(all[IndexDemoActive])
      case Chip.NotArchived  => base.except(state[IndexDemoArchived])
    val required = project match
      case Some(p) => filtered.require.project(p)
      case None    => filtered
    val sorted = sort match
      case SortPick.Edited => required.sort(IndexSort.EditedDesc)
      case SortPick.Rank   => required.sort(IndexSort.RankDesc)
    val ranked = rankMin match
      case Some(n) => sorted.rankMin(n)
      case None    => sorted
    startAfter match
      case Some(c) => ranked.limit(limit).startAfter(c)
      case None    => ranked.limit(limit)
  end queryOf

  private val testId = AttrKey("data-testid", Codec.StringAsIs)

  final case class InboxRow(indexed: Indexed[String], number: String):
    def instanceId: String = indexed.instanceId
    def stateName: String  = indexed.stateName
    def rank: Long         = indexed.rank

  private def run[A](zio: ZIO[Any, MechanoidError, A]): UIO[A] =
    zio.mapError(e => new RuntimeException(e.toString)).orDie

  def panel(
      index: InstanceIndex[String],
      birth: Ticket => IO[MechanoidError, String],
      send: (String, TicketEvent) => IO[MechanoidError, Unit],
      lookupState: String => UIO[Option[Ticket]],
      note: ascent.ast.UI[Any],
  ): UIO[ascent.ast.UI[Any]] =
    for
      n0       <- Random.nextIntBounded(900).map(_ + 100)
      number   <- sq(s"t-$n0")
      assignee <- sq(Me)
      project  <- sq(Alpha)
      priority <- sq(3)
      chip     <- sq[Chip](Chip.All)
      sort     <- sq[SortPick](SortPick.Edited)
      minRank  <- sq(0)
      requireP <- sq(false)
      rows     <- sq(Seq.empty[InboxRow])
      counts   <- sq("")
      message  <- sq("")
      cursor   <- sq(Option.empty[IndexCursor[String]])
      hasMore  <- sq(false)
    yield
      def currentQuery(after: Option[IndexCursor[String]]) =
        for
          a   <- assignee.get
          c   <- chip.get
          p   <- project.get
          rp  <- requireP.get
          s   <- sort.get
          min <- minRank.get
        yield queryOf(
          assignee = a,
          chip = c,
          project = if rp then Some(p) else None,
          sort = s,
          rankMin = if min > 0 then Some(min.toLong) else None,
          startAfter = after,
        )

      def leaf(name: String): String =
        name.stripPrefix("IndexDemo").stripSuffix("$")

      def ticketNo(id: String): UIO[String] =
        run(index.aliasesOf(id, Some("number"))).map(_.headOption.map(_.key).getOrElse(id.take(8)))

      def toRows(items: Chunk[Indexed[String]]): UIO[List[InboxRow]] =
        ZIO.foreach(items.toList)(r => ticketNo(r.instanceId).map(InboxRow(r, _)))

      def refresh: UIO[Unit] =
        for
          q    <- currentQuery(None)
          a    <- assignee.get
          page <- run(index.find(q))
          n    <- run(index.count(q))
          by   <- run(index.countsByState(IndexQuery.of[IndexDemoTicket].assignee(a).toQuery[String].key))
          rs   <- toRows(page.items)
          _    <- rows.set(rs)
          _    <- cursor.set(page.cursor)
          _    <- hasMore.set(page.hasMore)
          _    <- counts.set(
            if n == 0 then "No matching tickets"
            else s"$n tickets · ${by.map((st, c) => s"$c ${leaf(st)}").mkString(", ")}"
          )
        yield ()

      def nextPage: UIO[Unit] =
        cursor.get.flatMap {
          case None    => ZIO.unit
          case Some(c) =>
            for
              q    <- currentQuery(Some(c))
              page <- run(index.find(q))
              more <- toRows(page.items)
              _    <- rows.update(_ ++ more)
              _    <- cursor.set(page.cursor)
              _    <- hasMore.set(page.hasMore)
            yield ()
        }

      def bumpNumber(n: String): String =
        val digits = n.reverse.takeWhile(_.isDigit).reverse
        if digits.isEmpty then s"$n-2"
        else n.dropRight(digits.length) + (digits.toInt + 1).toString

      def describe(e: MechanoidError): String =
        e match
          case UniqueAliasError(_, key, _, _) => s"$key is already taken"
          case other                          => other.toString

      def create: UIO[Unit] =
        message.set("creating") *>
          (for
            n <- number.get
            a <- assignee.get
            p <- project.get
            r <- priority.get
            _ <- birth(IndexDemoOpen(n, a, p, r))
            _ <- message.set(s"created $n")
            _ <- number.set(bumpNumber(n))
            _ <- refresh
          yield ()).foldCauseZIO(
            c =>
              message.set(
                c.failureOption.map(describe).orElse(c.dieOption.map(_.toString.take(160))).getOrElse("create failed")
              ),
            ZIO.succeed,
          )

      def doLookup: UIO[Unit] =
        number.get.flatMap { n =>
          lookupState(n).flatMap {
            case None    => message.set(s"$n not found")
            case Some(t) => message.set(s"$n is ${leaf(t.getClass.getSimpleName)}")
          }
        }

      def onClass(active: Squawk[Boolean]): Attr[Any] =
        A.className(active.map(v => if v then "is-on" else ""))

      def chipBtn(c: Chip, label: String, id: String) =
        E.button(
          testId(id),
          A.`type`("button"),
          onClass(chip.map(_ == c)),
          Events.onClick(_ => chip.set(c) *> refresh),
          label,
        )

      def field(label: String, body: ascent.ast.UI[Any]*) =
        E.div(
          A.className("mechanoid-ticket-field"),
          E.span(label),
          E.div(A.className("mechanoid-ticket-field-row"), ascent.ast.UI.Fragment(body.toVector)),
        )

      E.div(
        A.className("mechanoid-multitab-demo mechanoid-ticket-index-demo"),
        note,
        E.div(
          A.className("mechanoid-role-grid"),
          E.section(
            A.className("mechanoid-role-card"),
            E.h3("New ticket"),
            E.p(
              A.className("mechanoid-role-blurb"),
              "Ticket number must be unique. Create puts it in the index immediately.",
            ),
            field(
              "Number",
              E.input(
                testId("ticket-number"),
                A.`type`("text"),
                A.placeholder("t-1"),
                A.value(number),
                Events.onInput(e => number.set(e.targetValue.getOrElse(""))),
              ),
              E.button(testId("ticket-lookup"), A.`type`("button"), Events.onClick(_ => doLookup), "Lookup"),
            ),
            field(
              "Assignee",
              E.button(
                testId("ticket-assignee-me"),
                A.`type`("button"),
                onClass(assignee.map(_ == Me)),
                Events.onClick(_ => assignee.set(Me) *> refresh),
                "Me",
              ),
              E.button(
                testId("ticket-assignee-other"),
                A.`type`("button"),
                onClass(assignee.map(_ == Other)),
                Events.onClick(_ => assignee.set(Other) *> refresh),
                "Other",
              ),
            ),
            field(
              "Project",
              E.button(
                testId("ticket-project-alpha"),
                A.`type`("button"),
                onClass(project.map(_ == Alpha)),
                Events.onClick(_ => project.set(Alpha) *> refresh),
                "Alpha",
              ),
              E.button(
                testId("ticket-project-beta"),
                A.`type`("button"),
                onClass(project.map(_ == Beta)),
                Events.onClick(_ => project.set(Beta) *> refresh),
                "Beta",
              ),
            ),
            field(
              "Priority",
              E.input(
                testId("ticket-priority"),
                A.`type`("number"),
                A.value(priority.map(_.toString)),
                Events.onInput(e => priority.set(e.targetValue.flatMap(_.toIntOption).getOrElse(0))),
              ),
            ),
            E.div(
              A.className("mechanoid-live-actions"),
              E.button(
                testId("ticket-create"),
                A.className("mechanoid-ticket-create"),
                A.`type`("button"),
                Events.onClick(_ => create),
                "Create",
              ),
            ),
            E.p(testId("ticket-message"), A.className("mechanoid-ticket-status"), message),
          ),
          E.section(
            A.className("mechanoid-role-card"),
            E.h3("Inbox"),
            E.p(A.className("mechanoid-role-blurb"), "The list is an index query, not tickets kept in this panel."),
            field(
              "State",
              chipBtn(Chip.All, "Any", "ticket-chip-all"),
              chipBtn(Chip.ActiveOnly, "Open", "ticket-chip-active"),
              chipBtn(Chip.ArchivedOnly, "Archived", "ticket-chip-archived"),
              chipBtn(Chip.NotArchived, "Not archived", "ticket-chip-except"),
            ),
            field(
              "Project",
              E.button(
                testId("ticket-require-project"),
                A.`type`("button"),
                onClass(requireP),
                Events.onClick(_ => requireP.update(!_) *> refresh),
                Squawk.zipWith(requireP, project)((on, p) => if on then s"Only $p" else "Any project"),
              ),
            ),
            field(
              "Sort",
              E.button(
                testId("ticket-sort-edited"),
                A.`type`("button"),
                onClass(sort.map(_ == SortPick.Edited)),
                Events.onClick(_ => sort.set(SortPick.Edited) *> refresh),
                "Edited",
              ),
              E.button(
                testId("ticket-sort-rank"),
                A.`type`("button"),
                onClass(sort.map(_ == SortPick.Rank)),
                Events.onClick(_ => sort.set(SortPick.Rank) *> refresh),
                "Priority",
              ),
            ),
            field(
              "Min priority",
              E.input(
                testId("ticket-rank-min"),
                A.`type`("number"),
                A.value(minRank.map(_.toString)),
                Events.onInput(e => minRank.set(e.targetValue.flatMap(_.toIntOption).getOrElse(0)) *> refresh),
              ),
            ),
            E.p(testId("ticket-counts"), A.className("mechanoid-ticket-counts"), counts),
            when(rows.map(_.isEmpty))(
              E.p(A.className("mechanoid-ticket-empty"), "No tickets for this query.")
            ),
            E.ul(
              testId("ticket-rows"),
              A.className("mechanoid-ticket-list"),
              forEach(rows)(r => s"${r.instanceId}:${r.stateName}") { row =>
                E.li(
                  testId(s"ticket-row-${row.instanceId}"),
                  A.className("mechanoid-ticket-row"),
                  E.strong(A.className("mechanoid-ticket-row-id"), row.number),
                  E.span(A.className("mechanoid-ticket-row-state"), leaf(row.stateName)),
                  E.span(A.className("mechanoid-ticket-row-meta"), s"priority ${row.rank}"),
                  E.div(
                    A.className("mechanoid-live-actions"),
                    E.button(
                      testId(s"ticket-archive-${row.instanceId}"),
                      A.`type`("button"),
                      Events.onClick(_ =>
                        send(row.instanceId, Archive).foldZIO(
                          e => message.set(describe(e)),
                          _ => message.set("") *> refresh,
                        )
                      ),
                      "Archive",
                    ),
                    E.button(
                      testId(s"ticket-reopen-${row.instanceId}"),
                      A.`type`("button"),
                      Events.onClick(_ =>
                        send(row.instanceId, Reopen).foldZIO(
                          e => message.set(describe(e)),
                          _ => message.set("") *> refresh,
                        )
                      ),
                      "Reopen",
                    ),
                  ),
                )
              },
            ),
            when(hasMore)(
              E.div(
                A.className("mechanoid-live-actions"),
                E.button(testId("ticket-next"), A.`type`("button"), Events.onClick(_ => nextPage), "More"),
              )
            ),
            E.p(
              testId("ticket-has-more"),
              A.className("mechanoid-ticket-counts"),
              hasMore.map(m => if m then "more" else ""),
            ),
          ),
        ),
      )
end TicketIndexDemoUi
