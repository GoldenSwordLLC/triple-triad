package triad.net

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import triad.core.MatchSettings

val wireJson = Json { ignoreUnknownKeys = true }

@Serializable enum class ColorPref { blue, red, any }

@Serializable sealed class Msg {
    @Serializable @SerialName("hello")     data class Hello(val name: String, val token: String = "", val proto: Int = 1) : Msg()
    @Serializable @SerialName("commit")    data class Commit(val hash: String) : Msg()
    @Serializable @SerialName("reveal")    data class Reveal(val nonce: String) : Msg()
    @Serializable @SerialName("settings")  data class Settings(val settings: MatchSettings) : Msg()
    @Serializable @SerialName("accept")    data object Accept : Msg()
    @Serializable @SerialName("decline")   data object Decline : Msg()
    @Serializable @SerialName("deck")      data class Deck(val ids: List<String>, val bans: List<String>, val pref: ColorPref) : Msg()
    @Serializable @SerialName("deckAck")   data object DeckAck : Msg()
    @Serializable @SerialName("move")      data class Move(val handIndex: Int, val cell: Int, val card: String, val seq: Int, val hash: String) : Msg()
    @Serializable @SerialName("tradePick") data class TradePick(val ids: List<String>) : Msg()
    @Serializable @SerialName("fOffer")   data class FriendlyOffer(val ids: List<String>) : Msg()
    @Serializable @SerialName("fAccept")  data object FriendlyAccept : Msg()
    @Serializable @SerialName("resign")    data object Resign : Msg()
    @Serializable @SerialName("error")     data class Error(val code: String, val detail: String = "") : Msg()
}
