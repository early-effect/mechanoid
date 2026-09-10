package mechanoid.persistence

/** A unique secondary key that resolves to one FSM instance.
  *
  * Namespaces are caller-defined (`"campaign"`, `"template"`). Uniqueness is per `(namespace, key)` inside one
  * [[InstanceIndex]], the same way instance ids are unique inside one [[EventStore]].
  *
  * {{{
  * Alias("campaign", "camp-123")  // -> some InitiativeId
  * }}}
  */
final case class Alias(namespace: String, key: String):
  override def toString: String = s"$namespace/$key"
