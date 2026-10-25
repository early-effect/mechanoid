package mechanoid.examples.petstore

import zio.json.*

// ============================================
// Domain Types
// ============================================

case class Pet(id: String, name: String, species: String, price: BigDecimal)
object Pet:
  given JsonCodec[Pet] = DeriveJsonCodec.gen[Pet]

  val catalog = List(
    Pet("pet-1", "Whiskers", "Cat", BigDecimal(150.00)),
    Pet("pet-2", "Buddy", "Dog", BigDecimal(250.00)),
    Pet("pet-3", "Goldie", "Fish", BigDecimal(25.00)),
    Pet("pet-4", "Tweety", "Bird", BigDecimal(75.00)),
    Pet("pet-5", "Hoppy", "Rabbit", BigDecimal(100.00)),
  )
end Pet

case class Customer(id: String, name: String, email: String, address: String)
object Customer:
  given JsonCodec[Customer] = DeriveJsonCodec.gen[Customer]

  val samples = List(
    Customer("cust-1", "Alice Smith", "alice@example.com", "123 Main St, Springfield"),
    Customer("cust-2", "Bob Jones", "bob@example.com", "456 Oak Ave, Riverside"),
    Customer("cust-3", "Carol White", "carol@example.com", "789 Pine Rd, Lakewood"),
  )
end Customer

/** Order data stored with the FSM */
case class OrderData(
    orderId: Int,
    pet: Pet,
    customer: Customer,
    correlationId: String,
    messageId: String,
)
object OrderData:
  given JsonCodec[OrderData] = DeriveJsonCodec.gen[OrderData]
