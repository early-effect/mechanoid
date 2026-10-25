package mechanoid.examples

import zio.*
import zio.Console.printLine
import mechanoid.*
import mechanoid.stores.InMemoryEventStore
import mechanoid.examples.petstore.*
import java.time.format.DateTimeFormatter
import java.nio.file.{Files, Paths}

/** Pet Store Demo Application
  *
  * Demonstrates Mechanoid FSM patterns:
  *   - `.onEntry` for synchronous logging/metrics
  *   - `.producing` for async service calls that drive the FSM forward
  *   - Durable timeouts for payment deadlines
  *   - Self-driving FSMs where services return result events
  *
  * The FSM processes orders through: Created -> PaymentProcessing -> Paid -> ShippingRequested -> Shipped -> Delivered
  *
  * Run with: sbt "examples/runMain mechanoid.examples.PetStoreApp"
  */
object PetStoreApp extends ZIOAppDefault:

  // ============================================
  // ANSI Colors for Console Output
  // ============================================

  object Colors:
    val Reset   = "\u001b[0m"
    val Bold    = "\u001b[1m"
    val Dim     = "\u001b[2m"
    val Red     = "\u001b[31m"
    val Green   = "\u001b[32m"
    val Yellow  = "\u001b[33m"
    val Blue    = "\u001b[34m"
    val Magenta = "\u001b[35m"
    val Cyan    = "\u001b[36m"

    def success(s: String)   = s"$Green$s$Reset"
    def error(s: String)     = s"$Red$s$Reset"
    def warning(s: String)   = s"$Yellow$s$Reset"
    def info(s: String)      = s"$Cyan$s$Reset"
    def highlight(s: String) = s"$Bold$Magenta$s$Reset"
    def dim(s: String)       = s"$Dim$s$Reset"
    def header(s: String)    = s"$Bold$Blue$s$Reset"
  end Colors

  import Colors.*

  // ============================================
  // Logging Services with Colors
  // ============================================

  case class LoggingPaymentProcessor(delegate: PaymentProcessor) extends PaymentProcessor:
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    def processPayment(customerName: String, amount: BigDecimal, method: String): IO[PaymentError, PaymentSuccess] =
      for
        ts <- Clock.instant.map(i => timeFormatter.format(i.atZone(java.time.ZoneId.systemDefault())))
        _  <- printLine(
          s"${dim(ts)} ${Green}[PAYMENT]$Reset Processing $$${amount} for ${highlight(customerName)}"
        ).orDie
        result <- delegate.processPayment(customerName, amount, method)
        _ <- printLine(s"${dim(ts)} ${Green}[PAYMENT]$Reset ${success(s"Approved: ${result.transactionId}")}").orDie
      yield result
  end LoggingPaymentProcessor

  case class LoggingShipper(delegate: Shipper) extends Shipper:
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    def requestShipment(petName: String, customerName: String, address: String): IO[ShippingError, ShipmentAccepted] =
      for
        ts <- Clock.instant.map(i => timeFormatter.format(i.atZone(java.time.ZoneId.systemDefault())))
        _  <- printLine(
          s"${dim(ts)} ${Blue}[SHIPPING]$Reset Requesting shipment of ${highlight(petName)} to $customerName"
        ).orDie
        result <- delegate.requestShipment(petName, customerName, address)
        _      <- printLine(
          s"${dim(ts)} ${Blue}[SHIPPING]$Reset ${success(s"Dispatched: ${result.trackingId} via ${result.carrier}")}"
        ).orDie
      yield result
  end LoggingShipper

  case class LoggingNotificationService(delegate: NotificationService) extends NotificationService:
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    def sendNotification(email: String, notifType: String): IO[NotificationError, Unit] =
      for
        ts <- Clock.instant.map(i => timeFormatter.format(i.atZone(java.time.ZoneId.systemDefault())))
        _  <- printLine(s"${dim(ts)} ${Magenta}[NOTIFY]$Reset Sending '$notifType' to ${info(email)}").orDie
        _  <- delegate.sendNotification(email, notifType)
      yield ()
  end LoggingNotificationService

  // ============================================
  // Order Processing
  // ============================================

  // Type alias for FSM runtime layers
  type OrderFSMLayers = EventStore[Int, OrderState, OrderEvent] & TimeoutStrategy[Int] & LockingStrategy[Int]

  def processOrder(
      orderId: Int,
      pet: Pet,
      customer: Customer,
      machine: Machine[OrderState, OrderEvent],
  ): ZIO[Scope & OrderFSMLayers, Throwable | MechanoidError, OrderState] =
    import OrderEvent.*
    import OrderState.*

    for
      _ <- printLine("").orDie
      _ <- printLine(
        s"${Bold}═══ Order $orderId: ${customer.name} buying ${pet.name} (${pet.species}) for $$${pet.price} ═══$Reset"
      ).orDie
      correlationId <- Random.nextUUID.map(_.toString)
      messageId     <- Random.nextUUID.map(_.toString)

      // Create FSM runtime - layers provided via ZIO environment
      fsm <- FSMRuntime(orderId, machine, Created)

      // Send the initial event - the FSM will self-drive from here via .producing effects
      initiateEvent = InitiatePayment(
        orderId = orderId,
        customerId = customer.id,
        customerName = customer.name,
        customerEmail = customer.email,
        customerAddress = customer.address,
        petName = pet.name,
        amount = pet.price,
        paymentMethod = "Visa ****4242",
        correlationId = correlationId,
        messageId = messageId,
      )
      _ <- fsm.send(initiateEvent)

      // Wait for the FSM to reach a terminal state (Delivered or Cancelled)
      // The .producing effects will drive it forward automatically
      finalState <- waitForTerminalState(fsm, orderId, 30)

      _ <- printLine(s"${Bold}Order $orderId final state: ${stateColor(finalState)}$Reset").orDie
    yield finalState
    end for
  end processOrder

  def waitForTerminalState(
      fsm: FSMRuntime[Int, OrderState, OrderEvent],
      orderId: Int,
      maxAttempts: Int,
  ): ZIO[Any, MechanoidError, OrderState] =
    import OrderState.*

    def loop(attempts: Int): ZIO[Any, MechanoidError, OrderState] =
      for
        state  <- fsm.currentState
        result <- state match
          case Delivered | Cancelled        => ZIO.succeed(state)
          case _ if attempts >= maxAttempts =>
            ZIO.logWarning(s"Order $orderId: Max attempts reached, current state: $state") *>
              ZIO.succeed(state)
          case _ =>
            ZIO.sleep(200.millis) *> loop(attempts + 1)
      yield result

    loop(0)
  end waitForTerminalState

  def stateColor(state: OrderState): String =
    import OrderState.*
    state match
      case Created           => dim(state.toString)
      case PaymentProcessing => warning(state.toString)
      case Paid              => info(state.toString)
      case ShippingRequested => info(state.toString)
      case Shipped           => info(state.toString)
      case Delivered         => success(state.toString)
      case Cancelled         => error(state.toString)

  // ============================================
  // Visualization Generation
  // ============================================

  def generateVisualizations(
      orderResults: List[(Int, OrderState)]
  ): ZIO[EventStore[Int, OrderState, OrderEvent], Throwable | MechanoidError, Unit] =
    val vizDir  = Paths.get("docs", "visualizations")
    val machine = OrderFSM.simpleStructure

    for
      eventStore <- ZIO.service[EventStore[Int, OrderState, OrderEvent]]
      _          <- ZIO.attempt(Files.createDirectories(vizDir))

      // Generate FSM structure diagram
      stateDiagram = MermaidVisualizer.stateDiagram(machine, Some(OrderState.Created))
      flowchart    = MermaidVisualizer.flowchart(machine)
      graphviz     = GraphVizVisualizer.digraph(machine, initialState = Some(OrderState.Created))

      structureMd = s"""# Order FSM Structure
                       |
                       |This diagram shows the Pet Store order lifecycle FSM.
                       |
                       |## State Diagram (Mermaid)
                       |
                       |```mermaid
                       |$stateDiagram
                       |```
                       |
                       |## Flowchart (Mermaid)
                       |
                       |```mermaid
                       |$flowchart
                       |```
                       |
                       |## GraphViz DOT
                       |
                       |```dot
                       |$graphviz
                       |```
                       |""".stripMargin

      _ <- ZIO.attempt(Files.writeString(vizDir.resolve("order-fsm-structure.md"), structureMd))

      // Generate execution traces for each order
      _ <- ZIO.foreachDiscard(orderResults) { case (orderId, finalState) =>
        for
          events <- eventStore.loadEvents(orderId).runCollect.map(_.toList)
          trace = buildExecutionTrace(orderId, events, finalState)

          sequenceDiagram = MermaidVisualizer.sequenceDiagram(
            trace,
            summon[Finite[OrderState]],
            summon[Finite[OrderEvent]],
          )

          flowchartWithTrace = MermaidVisualizer.flowchart(machine, Some(trace))

          traceMd = s"""# Order $orderId Execution Trace
                       |
                       |**Final State:** $finalState
                       |
                       |## Sequence Diagram
                       |
                       |```mermaid
                       |$sequenceDiagram
                       |```
                       |
                       |## Flowchart with Execution Path
                       |
                       |```mermaid
                       |$flowchartWithTrace
                       |```
                       |""".stripMargin

          _ <- ZIO.attempt(Files.writeString(vizDir.resolve(s"order-$orderId-trace.md"), traceMd))
        yield ()
      }

      _ <- printLine(s"\n${info("Visualization files written to docs/visualizations/")}").orDie
    yield ()
    end for
  end generateVisualizations

  def buildExecutionTrace(
      orderId: Int,
      events: List[StoredEvent[Int, OrderEvent]],
      finalState: OrderState,
  ): ExecutionTrace[OrderState, OrderEvent] =
    import OrderState.*

    val steps = events.zipWithIndex.map { case (stored, idx) =>
      val fromState = if idx == 0 then Created else getStateAfterEvent(events.take(idx))
      val toState   = getStateAfterEvent(events.take(idx + 1))
      TraceStep[OrderState, OrderEvent](
        sequenceNumber = idx + 1,
        from = fromState,
        to = toState,
        event = stored.event,
        timestamp = stored.timestamp,
        isTimeout = false,
      )
    }

    ExecutionTrace[OrderState, OrderEvent](
      instanceId = s"Order-$orderId",
      initialState = Created,
      currentState = finalState,
      steps = steps,
    )
  end buildExecutionTrace

  def getStateAfterEvent(events: List[StoredEvent[Int, OrderEvent]]): OrderState =
    import OrderState.*, OrderEvent.*
    events.foldLeft[OrderState](Created) { (state, stored) =>
      (state, stored.event) match
        case (Created, _: InitiatePayment)              => PaymentProcessing
        case (PaymentProcessing, _: PaymentSucceeded)   => Paid
        case (PaymentProcessing, _: PaymentFailed)      => Cancelled
        case (PaymentProcessing, PaymentTimeout)        => Cancelled
        case (Paid, _: RequestShipping)                 => ShippingRequested
        case (ShippingRequested, _: ShipmentDispatched) => Shipped
        case (Shipped, _: DeliveryConfirmed)            => Delivered
        case _                                          => state
    }

  // ============================================
  // Main Application
  // ============================================

  def run: ZIO[Any, Any, Unit] =
    // Create services with 85% success rate for demo variety
    val paymentProcessor = LoggingPaymentProcessor(PaymentProcessor.make(PaymentProcessor.Config(successRate = 0.85)))
    val shipper          = LoggingShipper(Shipper.make(Shipper.Config(successRate = 0.95)))
    val notificationService = LoggingNotificationService(NotificationService.make(NotificationService.Config()))

    // Create the FSM machine with injected services
    val machine = OrderFSM.machine(paymentProcessor, shipper, notificationService)

    // Define the program with its layer requirements
    def program: ZIO[OrderFSMLayers, Throwable | MechanoidError, Unit] =
      for
        _ <- printLine(s"""
          |${Bold}${Cyan}╔════════════════════════════════════════════════════════════════════╗
          |║                         PET STORE DEMO                             ║
          |║                                                                    ║
          |║  Demonstrating Mechanoid FSM patterns:                             ║
          |║  • .onEntry - synchronous logging/metrics                          ║
          |║  • .producing - async service calls that drive FSM forward         ║
          |║  • Self-driving FSMs via event-producing effects                   ║
          |║  • Visualization generation                                        ║
          |╚════════════════════════════════════════════════════════════════════╝$Reset
          |""".stripMargin).orDie

        // Process several orders (each scoped independently)
        orderResults <- ZIO.foreach(List(1, 2, 3, 4, 5)) { orderId =>
          val petIdx      = (orderId - 1) % Pet.catalog.length
          val customerIdx = (orderId - 1) % Customer.samples.length
          val pet         = Pet.catalog(petIdx)
          val customer    = Customer.samples(customerIdx)

          ZIO
            .scoped(processOrder(orderId, pet, customer, machine))
            .map(state => orderId -> state)
            .catchAll { err =>
              printLine(s"${error(s"Order $orderId failed: $err")}").orDie *>
                ZIO.succeed(orderId -> OrderState.Cancelled)
            }
        }

        // Display summary
        _ <- printLine("").orDie
        _ <- printLine(s"${Bold}════════════════════════════════════════════════════════════════════$Reset").orDie
        _ <- printLine(s"${Bold}Order Summary:$Reset").orDie
        _ <- ZIO.foreachDiscard(orderResults) { case (orderId, state) =>
          printLine(s"  Order $orderId: ${stateColor(state)}").orDie
        }
        delivered = orderResults.count(_._2 == OrderState.Delivered)
        cancelled = orderResults.count(_._2 == OrderState.Cancelled)
        _ <- printLine(s"\n  ${success(s"Delivered: $delivered")} | ${error(s"Cancelled: $cancelled")}").orDie
        _ <- printLine(s"${Bold}════════════════════════════════════════════════════════════════════$Reset").orDie

        // Generate visualizations
        _ <- printLine(s"\n${info("Generating visualization files...")}").orDie
        _ <- generateVisualizations(orderResults)

        _ <- printLine(s"\n${Bold}${Green}Demo complete!$Reset").orDie
      yield ()

    // Provide all layers at the edge
    program.provide(
      InMemoryEventStore.unboundedLayer[Int, OrderState, OrderEvent],
      TimeoutStrategy.fiber[Int],
      LockingStrategy.optimistic[Int],
    )
  end run

end PetStoreApp
