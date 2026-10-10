package nl.amony.lib.messagebus

import io.circe.{Decoder, Encoder, Json}

/** Encodes a domain event to, and decodes it from, the JSON payload persisted in the outbox. */
trait PersistenceCodec[E]:

  def encode(event: E): Json

  def decode(json: Json): E

object PersistenceCodec:

  def fromCirce[E](using encoder: Encoder[E], decoder: Decoder[E]): PersistenceCodec[E] =
    new PersistenceCodec[E]:
      override def encode(event: E): Json = encoder(event)
      override def decode(json: Json): E  = decoder.decodeJson(json).fold(throw _, identity)
