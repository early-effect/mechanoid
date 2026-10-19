package mechanoid.examples.petstore

import zio.*
import mechanoid.*

// ============================================
// Order FSM - States and Events
// ============================================

/** Order lifecycle states - simple status indicators */
enum OrderState derives Finite:
  case Created
  case PaymentProcessing
  case Paid
  case ShippingRequested
  case Shipped
  case Delivered
  case Cancelled

/** Order lifecycle events with rich metadata.
  *
  * Events carry all context needed for effects. The suite-style DSL uses `event[T]` to match on TYPE (shape), ignoring
  * the parameter values. This allows the Machine definition to be declarative while runtime events carry actual data.
  *
  * Timeout events are simple case objects - they're fired automatically by the FSM runtime when a state's timeout
  * expires. Define handlers for them like any other event.
  *
  * Example:
  * {{{
  * // DSL matches ANY PaymentSucceeded by type
  * (PaymentProcessing via event[PaymentSucceeded] to Paid)
  *   .onEntry { (e, _) => ZIO.logInfo(s"Payment succeeded: ${e.transactionId}") }
  *
  * // Timeout handling - simple case object
  * PaymentProcessing via PaymentTimeout to Cancelled
  *
  * // At runtime, send with actual transaction data
  * fsm.send(PaymentSucceeded(orderId, txnId, customerId, customerName, ...))
  * }}}
  */
enum OrderEvent derives Finite:
  /** Initiate payment - carries full order context */
  case InitiatePayment(
      orderId: Int,
      customerId: String,
      customerName: String,
      customerEmail: String,
      customerAddress: String,
      petName: String,
      amount: BigDecimal,
      paymentMethod: String,
      correlationId: String,
      messageId: String,
  )

  /** Payment completed - carries context for shipping/notification */
  case PaymentSucceeded(
      orderId: Int,
      transactionId: String,
      customerId: String,
      customerName: String,
      customerEmail: String,
      customerAddress: String,
      petName: String,
      correlationId: String,
      messageId: String,
  )

  /** Payment failed with error reason */
  case PaymentFailed(orderId: Int, reason: String)

  /** Request shipping to an address */
  case RequestShipping(orderId: Int, address: String)

  /** Shipment dispatched - carries tracking info */
  case ShipmentDispatched(
      orderId: Int,
      trackingId: String,
      carrier: String,
      eta: String,
      customerEmail: String,
      customerName: String,
      petName: String,
      messageId: String,
  )

  /** Delivery confirmed at a timestamp */
  case DeliveryConfirmed(orderId: Int, timestamp: String)

  // ═══════════════════════════════════════════════════════════════════
  // Timeout events - fired automatically when state timeout expires
  // ═══════════════════════════════════════════════════════════════════

  /** Payment processing timed out - order will be cancelled */
  case PaymentTimeout

  /** Shipping request timed out - escalate to operations */
  case ShippingTimeout
end OrderEvent

/** Order FSM with timeouts and per-transition effects.
  *
  * This FSM demonstrates two key side effect patterns:
  *
  * '''`.onEntry`''' - Synchronous effects that run during the transition:
  *   - Used for logging, metrics, validation
  *   - Failures cause ActionFailedError and prevent the transition
  *
  * '''`.producing`''' - Async effects that produce events sent back to the FSM:
  *   - Used for external service calls (payment, shipping, notifications)
  *   - Fire-and-forget: forked as daemon fiber
  *   - Errors are logged but don't fail the original transition
  *   - Perfect for self-driving FSMs where services return result events
  *
  * '''Timeouts''' are configured using `@@ Aspect.timeout(duration, event)`. When the FSM enters the target state, a
  * timer starts. If no event fires before timeout, the specified event is automatically sent.
  */
object OrderFSM:
  import OrderState.*
  import OrderEvent.*

  /** Create the Order FSM machine with injected services.
    *
    * The machine uses `.producing` effects to call external services asynchronously. These effects run as daemon fibers
    * and produce events that are automatically sent back to the FSM, driving it forward.
    *
    * @param paymentProcessor
    *   Service for processing payments
    * @param shipper
    *   Service for requesting shipments
    * @param notificationService
    *   Service for sending notifications
    */
  def machine(
      paymentProcessor: PaymentProcessor,
      shipper: Shipper,
      notificationService: NotificationService,
  ): Machine[OrderState, OrderEvent] = Machine(assemblyAll[OrderState, OrderEvent]:
    // ═══════════════════════════════════════════════════════════════════
    // State machine transitions with effects
    // ═══════════════════════════════════════════════════════════════════

    // Created -> PaymentProcessing: initiate payment asynchronously
    // The .producing effect calls the payment service and returns the result event
    // Payment must complete within 5 minutes or order is cancelled
    (Created via event[InitiatePayment] to PaymentProcessing)
      .onEntry { (e, _) =>
        e match
          case ip: InitiatePayment =>
            ZIO.logInfo(s"Order ${ip.orderId}: Processing payment of $$${ip.amount} via ${ip.paymentMethod}")
          case _ => ZIO.unit
      }
      .producing { (e, _) =>
        e match
          case ip: InitiatePayment =>
            paymentProcessor
              .processPayment(ip.customerName, ip.amount, ip.paymentMethod)
              .map { success =>
                PaymentSucceeded(
                  orderId = ip.orderId,
                  transactionId = success.transactionId,
                  customerId = ip.customerId,
                  customerName = ip.customerName,
                  customerEmail = ip.customerEmail,
                  customerAddress = ip.customerAddress,
                  petName = ip.petName,
                  correlationId = ip.correlationId,
                  messageId = ip.messageId,
                )
              }
              .catchAll { err =>
                ZIO.succeed(PaymentFailed(ip.orderId, err.toString))
              }
          case _ => ZIO.succeed(PaymentFailed(0, "Unexpected event type"))
      } @@ Aspect.timeout(5.minutes, PaymentTimeout)

    // PaymentProcessing -> Paid: payment succeeded, request shipping
    // The .producing effect sends notification and triggers shipping request
    (PaymentProcessing via event[PaymentSucceeded] to Paid)
      .onEntry { (e, _) =>
        e match
          case ps: PaymentSucceeded =>
            ZIO.logInfo(s"Order ${ps.orderId}: Payment succeeded (${ps.transactionId})")
          case _ => ZIO.unit
      }
      .producing { (e, _) =>
        e match
          case ps: PaymentSucceeded =>
            // Send order confirmation notification, then trigger shipping request
            notificationService.sendNotification(ps.customerEmail, "order_confirmed").ignore *>
              ZIO.succeed(RequestShipping(ps.orderId, ps.customerAddress))
          case _ => ZIO.succeed(RequestShipping(0, ""))
      }

    // PaymentProcessing -> Cancelled: payment failed
    (PaymentProcessing via event[PaymentFailed] to Cancelled)
      .onEntry { (e, _) =>
        e match
          case pf: PaymentFailed =>
            ZIO.logWarning(s"Order ${pf.orderId}: Payment failed - ${pf.reason}")
          case _ => ZIO.unit
      }

    // PaymentProcessing -> Cancelled: payment timed out
    (PaymentProcessing via PaymentTimeout to Cancelled)
      .onEntry { (_, _) =>
        ZIO.logWarning("Payment timed out, order cancelled")
      }

    // Paid -> ShippingRequested: request shipment from carrier
    // The .producing effect calls the shipping service and returns ShipmentDispatched
    // Shipping must be dispatched within 24 hours
    (Paid via event[RequestShipping] to ShippingRequested)
      .onEntry { (e, _) =>
        e match
          case rs: RequestShipping =>
            ZIO.logInfo(s"Order ${rs.orderId}: Requesting shipment to ${rs.address}")
          case _ => ZIO.unit
      }
      .producing { (e, _) =>
        e match
          case rs: RequestShipping =>
            // Call the shipper service - it returns ShipmentAccepted with tracking info
            shipper
              .requestShipment("Pet", "Customer", rs.address)
              .map { accepted =>
                ShipmentDispatched(
                  orderId = rs.orderId,
                  trackingId = accepted.trackingId,
                  carrier = accepted.carrier,
                  eta = accepted.estimatedDelivery,
                  customerEmail = "", // Not available in RequestShipping
                  customerName = "",
                  petName = "",
                  messageId = "",
                )
              }
              .catchAll { err =>
                // On shipping failure, log and retry (timeout will eventually fire)
                ZIO.logWarning(s"Shipping request failed: $err") *>
                  ZIO.never // Don't produce an event - let timeout handle it
              }
          case _ => ZIO.never
      } @@ Aspect.timeout(24.hours, ShippingTimeout)

    // ShippingRequested -> Shipped: shipment dispatched
    // The .producing effect sends shipping notification
    (ShippingRequested via event[ShipmentDispatched] to Shipped)
      .onEntry { (e, _) =>
        e match
          case sd: ShipmentDispatched =>
            ZIO.logInfo(s"Order ${sd.orderId}: Shipped via ${sd.carrier}, tracking: ${sd.trackingId}")
          case _ => ZIO.unit
      }
      .producing { (e, _) =>
        e match
          case sd: ShipmentDispatched =>
            // Send shipping notification, then simulate delivery confirmation
            notificationService.sendNotification(sd.customerEmail, "shipped").ignore *>
              ZIO.sleep(100.millis) *> // Small delay to simulate delivery
              Clock.instant.map(now => DeliveryConfirmed(sd.orderId, now.toString))
          case _ => ZIO.succeed(DeliveryConfirmed(0, ""))
      }

    // ShippingRequested timeout: escalate to operations (stay in state, but notify)
    (ShippingRequested via ShippingTimeout to stay)
      .onEntry { (_, _) =>
        ZIO.logWarning("Shipping timeout - escalating to operations")
      }

    // Shipped -> Delivered: delivery confirmed (terminal state, no producing effect needed)
    (Shipped via event[DeliveryConfirmed] to Delivered)
      .onEntry { (e, _) =>
        e match
          case dc: DeliveryConfirmed =>
            ZIO.logInfo(s"Order ${dc.orderId}: Delivered at ${dc.timestamp}")
          case _ => ZIO.unit
      })

  /** Simple machine for visualization (no service dependencies).
    *
    * Use this when you just need the FSM structure for generating diagrams without running the FSM.
    */
  val simpleStructure: Machine[OrderState, OrderEvent] = Machine(assemblyAll[OrderState, OrderEvent]:
    (Created via event[InitiatePayment] to PaymentProcessing)
      .onEntry { (_, _) => ZIO.logInfo("Processing payment...") }
      @@ Aspect.timeout(5.minutes, PaymentTimeout)

    (PaymentProcessing via event[PaymentSucceeded] to Paid)
      .onEntry { (_, _) => ZIO.logInfo("Payment succeeded") }

    (PaymentProcessing via event[PaymentFailed] to Cancelled)
      .onEntry { (_, _) => ZIO.logWarning("Payment failed") }

    PaymentProcessing via PaymentTimeout to Cancelled

    (Paid via event[RequestShipping] to ShippingRequested)
      .onEntry { (_, _) => ZIO.logInfo("Shipping requested") }
      @@ Aspect.timeout(24.hours, ShippingTimeout)

    (ShippingRequested via event[ShipmentDispatched] to Shipped)
      .onEntry { (_, _) => ZIO.logInfo("Shipped") }

    (ShippingRequested via ShippingTimeout to stay)
      .onEntry { (_, _) => ZIO.logWarning("Shipping timeout") }

    (Shipped via event[DeliveryConfirmed] to Delivered)
      .onEntry { (_, _) => ZIO.logInfo("Delivered") })

end OrderFSM
