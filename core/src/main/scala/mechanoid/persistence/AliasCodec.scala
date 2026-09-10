package mechanoid.persistence

/** Encodes an alias field value as the string stored in [[InstanceIndex]].
  *
  * Provide a given for types whose `toString` is not a stable key. A low-priority given covers everything else via
  * `toString`.
  */
trait AliasCodec[A]:
  def encode(value: A): String

trait AliasCodecLowPriority:
  given toStringCodec[A]: AliasCodec[A] =
    (value: A) => String.valueOf(value)

object AliasCodec extends AliasCodecLowPriority:

  def apply[A](using codec: AliasCodec[A]): AliasCodec[A] = codec

  given string: AliasCodec[String] = (value: String) => value
