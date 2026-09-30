package app.spicetify.patches.spotify.extensions

import app.spicetify.patches.spotify.home.homePinsPatch
import app.spicetify.patches.spotify.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction32x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val PROVIDED = "Lcom/spotify/casita/v1/resolved/Provided;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val BUTTON = "Landroidx/appcompat/widget/AppCompatImageButton;"
private const val LIST = "Ljava/util/List;"
private const val DATA_POOL = "Lcom/spotify/kodiak/dataloader/DataPool;"

class ExtensionsPatchTest {
    @Test
    fun `reports each field number that changed`() {
        val apk = mapOf("A#X" to 1, "A#Y" to 3)
        assertEquals(listOf("A#Y expected 2, found 3"),
            fieldNumberMismatches(mapOf("A#X" to 1, "A#Y" to 2), apk::get))
    }

    @Test
    fun `reports a field whose class is missing`() {
        assertEquals(listOf("A#X missing"), fieldNumberMismatches(mapOf("A#X" to 1)) { null })
    }

    @Test
    fun `the extensions and Home pins both get the player bridge from one shared patch`() {
        assertTrue(playerBridgePatch in extensionsPatch.dependencies)
        assertTrue(playerBridgePatch in homePinsPatch.dependencies)
        // H1 calls into the extension, which the settings patch merges.
        assertTrue(settingsPatch in playerBridgePatch.dependencies)
        assertNull(playerBridgePatch.name, "internal, so Manager never lists it")
    }

    @Test
    fun `the protocol check covers the names and covers the Home shortcuts picker reads`() {
        val info = "Lspotify/your_library/proto/YourLibraryDecoratedEntityOuterClass\$YourLibraryEntityInfo;#"
        assertEquals(mapOf("NAME" to 2, "URI" to 3, "IMAGE_URI" to 6),
            esperantoFieldNumbers.filterKeys { it.startsWith(info) }
                .mapKeys { it.key.removePrefix(info).removeSuffix("_FIELD_NUMBER") })
    }

    @Test
    fun `the protocol check covers player errors, queue insertion, playability and the unplayable songs setting`() {
        val player = "Lcom/spotify/player/esperanto/proto/"
        val policy = "Lcom/spotify/playlist/policy/proto/"
        val cosmos = "Lcom/spotify/cosmos/util/"
        val expected = mapOf(
            "${player}EsGetStateRequest\$GetStateRequest;#PREV_TRACKS_CAP" to 1,
            "${player}EsGetStateRequest\$GetStateRequest;#NEXT_TRACKS_CAP" to 2,
            "${player}EsContextPlayerState\$ContextPlayerState;#NEXT_TRACKS" to 21,
            "${player}EsContextPlayerError\$ContextPlayerError;#CODE" to 1,
            "${player}EsContextPlayerError\$ContextPlayerError;#MESSAGE" to 2,
            "${player}EsContextPlayerError\$ContextPlayerError;#DATA" to 3,
            "${player}EsPlayAsNextInQueueRequest\$PlayAsNextInQueueRequest;#TRACKS" to 1,
            "${policy}PlaylistRequestDecorationPolicy;#TRACK" to 2,
            "${policy}PlaylistTrackDecorationPolicy;#TRACK" to 1,
            "${cosmos}policy/proto/TrackDecorationPolicy;#PLAYABLE" to 5,
            "${cosmos}policy/proto/TrackDecorationPolicy;#IS_LOCAL" to 13,
            "${policy}PlaylistItemDecorationPolicy;#ROW_ID" to 9,
            "Lcom/spotify/playlist/proto/PlaylistRequest\$Item;#TRACK_METADATA" to 4,
            "Lcom/spotify/playlist/proto/PlaylistRequest\$Item;#ROW_ID" to 7,
            "Lcom/spotify/playlist/proto/PlaylistRequest\$Item;#TRACK_PLAY_STATE" to 8,
            "${cosmos}proto/TrackMetadata;#PLAYABLE" to 6,
            "${cosmos}proto/TrackMetadata;#IS_LOCAL" to 11,
            "${cosmos}proto/TrackPlayState;#IS_PLAYABLE" to 1,
            "${cosmos}proto/TrackPlayState;#PLAYABILITY_RESTRICTION" to 2,
            "Lcom/spotify/settings/esperanto/proto/SettingsOuterClass\$SettingsState;#SHOW_UNAVAILABLE_TRACKS" to 17,
        ).mapKeys { it.key + "_FIELD_NUMBER" }
        assertEquals(expected, esperantoFieldNumbers.filterKeys { it in expected })
    }

    @Test
    fun `the protocol check covers skipping to a row by its uid`() {
        val player = "Lcom/spotify/player/esperanto/proto/"
        assertEquals(3, esperantoFieldNumbers["${player}EsSkipToTrack\$SkipToTrack;#TRACK_UID_FIELD_NUMBER"])
        assertEquals(2, esperantoFieldNumbers["${player}EsContextTrack\$ContextTrack;#UID_FIELD_NUMBER"])
    }

    @Test
    fun `the protocol check covers the details of a song from Spotify's core`() {
        val item = "Lcom/spotify/metadata/cosmos/proto/MetadataCosmos\$MetadataItem;"
        val metadata = "Lcom/spotify/metadata/proto/Metadata\$"
        val expected = mapOf(
            "$item#ERROR" to 1,
            "$item#TRACK" to 4,
            "${metadata}Track;#GID" to 1,
            "${metadata}Track;#NAME" to 2,
            "${metadata}Track;#ARTIST" to 4,
            "${metadata}Track;#DURATION" to 7,
            "${metadata}Track;#EXPLICIT" to 9,
            "${metadata}Track;#EXTERNAL_ID" to 10,
            "${metadata}Track;#ALTERNATIVE" to 13,
            "${metadata}Artist;#GID" to 1,
            "${metadata}Artist;#NAME" to 2,
            "${metadata}ExternalId;#TYPE" to 1,
            "${metadata}ExternalId;#ID" to 2,
        ).mapKeys { it.key + "_FIELD_NUMBER" }
        assertEquals(expected, esperantoFieldNumbers.filterKeys { it in expected })
    }

    @Test
    fun `the protocol check covers whether the player is playing or paused`() {
        val state = "Lcom/spotify/player/esperanto/proto/EsContextPlayerState\$ContextPlayerState;#"
        assertEquals(13, esperantoFieldNumbers["${state}IS_PLAYING_FIELD_NUMBER"])
        assertEquals(14, esperantoFieldNumbers["${state}IS_PAUSED_FIELD_NUMBER"])
    }

    @Test
    fun `hooks the return that follows initializeScheduling`() {
        assertEquals(2, bridgeHookIndex(listOf(schedule, schedule, returnVoid)))
    }

    @Test
    fun `refuses a constructor whose ending changed`() {
        assertNull(bridgeHookIndex(listOf(returnVoid)))
        assertNull(bridgeHookIndex(listOf(schedule, returnVoid, schedule, returnVoid)))
        assertNull(bridgeHookIndex(listOf(invoke(Opcode.INVOKE_DIRECT), returnVoid)))
        assertNull(bridgeHookIndex(listOf(invoke(Opcode.INVOKE_VIRTUAL, "shutdown"), returnVoid)))
        assertNull(bridgeHookIndex(listOf(invoke(Opcode.INVOKE_VIRTUAL, owner = "Lp/other;"), returnVoid)))
    }

    @Test
    fun `accepts the menu model the menu hooks insert before`() {
        assertTrue(isMenuModel(type(Opcode.NEW_INSTANCE, 1, "Lp/krj;")))
    }

    @Test
    fun `refuses any other instruction at a menu hook`() {
        assertFalse(isMenuModel(null))
        assertFalse(isMenuModel(type(Opcode.NEW_INSTANCE, 2, "Lp/krj;")))
        assertFalse(isMenuModel(type(Opcode.NEW_INSTANCE, 1, "Lp/other;")))
        assertFalse(isMenuModel(type(Opcode.CONST_CLASS, 1, "Lp/krj;")))
        assertFalse(isMenuModel(returnVoid))
    }

    @Test
    fun `accepts the instruction a Hide podcasts hook expects at its index`() {
        val section = "Lcom/spotify/casita/v1/resolved/Section;"
        val mapSection = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 4, 5, 0, 0, 0,
            ImmutableMethodReference("Lp/mz1;", "O", listOf(section), "Lp/n920;"))
        assertTrue(isHookSite(mapSection, Opcode.INVOKE_VIRTUAL, "Lp/mz1;->O($section)Lp/n920;"))
        assertTrue(isHookSite(type(Opcode.NEW_INSTANCE, 0, ARRAY_LIST), Opcode.NEW_INSTANCE, ARRAY_LIST))
        assertTrue(isHookSite(ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, 46), Opcode.MOVE_OBJECT_FROM16))
        assertTrue(isHookSite(field(Opcode.IPUT_OBJECT, 1, 0, "Lp/ipy;", "a", ARRAY_LIST),
            Opcode.IPUT_OBJECT, "Lp/ipy;->a:$ARRAY_LIST"))
    }

    @Test
    fun `refuses any other instruction at a Hide podcasts hook`() {
        assertFalse(isHookSite(null, Opcode.MOVE_OBJECT_FROM16))
        assertFalse(isHookSite(returnVoid, Opcode.MOVE_OBJECT_FROM16))
        assertFalse(isHookSite(type(Opcode.NEW_INSTANCE, 0, "Ljava/util/LinkedList;"), Opcode.NEW_INSTANCE, ARRAY_LIST))
        assertFalse(isHookSite(type(Opcode.CONST_CLASS, 0, ARRAY_LIST), Opcode.NEW_INSTANCE, ARRAY_LIST))
        assertFalse(isHookSite(type(Opcode.NEW_INSTANCE, 0, ARRAY_LIST), Opcode.NEW_INSTANCE))
        assertFalse(isHookSite(field(Opcode.IPUT_OBJECT, 1, 0, "Lp/ipy;", "b", ARRAY_LIST),
            Opcode.IPUT_OBJECT, "Lp/ipy;->a:$ARRAY_LIST"))
    }

    @Test
    fun `accepts the items getter P2 hooks`() {
        assertTrue(isItemsGetter(listOf(items(0), returnObject(0))))
    }

    @Test
    fun `refuses an items getter of any other shape`() {
        assertFalse(isItemsGetter(listOf(items(0))))
        assertFalse(isItemsGetter(listOf(items(0), returnObject(0), returnObject(0))))
        assertFalse(isItemsGetter(listOf(returnObject(0), items(0))))
        assertFalse(isItemsGetter(listOf(items(1), returnObject(1))))
        assertFalse(isItemsGetter(listOf(items(0), returnObject(1))))
        assertFalse(isItemsGetter(listOf(items(0), ImmutableInstruction11x(Opcode.THROW, 0))))
        assertFalse(isItemsGetter(listOf(field(Opcode.IGET_OBJECT, 0, 1, PROVIDED, "other_", "Lp/ih40;"), returnObject(0))))
    }

    @Test
    fun `accepts the end of the shuffle button's constructor that N1 goes in before`() {
        assertTrue(isShuffleButtonEnd(listOf(storeButton(2, 4), returnVoid), 1))
    }

    @Test
    fun `refuses any other end of the shuffle button's constructor`() {
        val end = listOf(storeButton(2, 4), returnVoid)
        assertFalse(isShuffleButtonEnd(end, 0))
        assertFalse(isShuffleButtonEnd(end, 2))
        assertFalse(isShuffleButtonEnd(listOf(storeButton(3, 4), returnVoid), 1))
        assertFalse(isShuffleButtonEnd(listOf(storeButton(2, 5), returnVoid), 1))
        assertFalse(isShuffleButtonEnd(listOf(field(Opcode.IPUT_OBJECT, 2, 4, "Lp/xkp;", "h", BUTTON), returnVoid), 1))
        assertFalse(isShuffleButtonEnd(listOf(field(Opcode.IGET_OBJECT, 2, 4, "Lp/xkp;", "i", BUTTON), returnVoid), 1))
        assertFalse(isShuffleButtonEnd(listOf(storeButton(2, 4), returnObject(2)), 1))
    }

    @Test
    fun `accepts the store of the list menu's item providers that M1 goes in before`() {
        assertTrue(isItemProvidersStore(storeProviders(4, 0)))
    }

    @Test
    fun `refuses any other instruction where M1 goes`() {
        assertFalse(isItemProvidersStore(null))
        assertFalse(isItemProvidersStore(storeProviders(3, 0)))
        assertFalse(isItemProvidersStore(storeProviders(4, 1)))
        assertFalse(isItemProvidersStore(field(Opcode.IPUT_OBJECT, 4, 0, "Lp/sv70;", "c", LIST)))
        assertFalse(isItemProvidersStore(field(Opcode.IGET_OBJECT, 4, 0, "Lp/sv70;", "d", LIST)))
        assertFalse(isItemProvidersStore(returnVoid))
    }

    @Test
    fun `accepts the copy of Home's chips that A goes in before`() {
        assertTrue(isChipsCopy(listOf(chips(1), copy(2)), 1))
    }

    @Test
    fun `refuses any other place for A`() {
        val site = listOf(chips(1), copy(2))
        assertFalse(isChipsCopy(site, 0))
        assertFalse(isChipsCopy(site, 2))
        assertFalse(isChipsCopy(listOf(chips(0), copy(2)), 1))
        assertFalse(isChipsCopy(listOf(ImmutableInstruction11x(Opcode.MOVE_RESULT, 1), copy(2)), 1))
        assertFalse(isChipsCopy(listOf(chips(1), copy(1)), 1))
        assertFalse(isChipsCopy(listOf(chips(1), type(Opcode.NEW_INSTANCE, 2, "Ljava/util/LinkedList;")), 1))
        assertFalse(isChipsCopy(listOf(chips(1), type(Opcode.CONST_CLASS, 2, ARRAY_LIST)), 1))
    }

    @Test
    fun `accepts the send of a chip tap that B goes in before, and the return B skips to`() {
        assertTrue(isChipTapSend(listOf(sendTap(3, 2), returnObject(13)), 0, 1))
    }

    @Test
    fun `refuses any other place for B`() {
        val site = listOf(sendTap(3, 2), returnObject(13))
        assertFalse(isChipTapSend(site, 1, 1))
        assertFalse(isChipTapSend(site, 0, 0))
        assertFalse(isChipTapSend(site, 0, 2))
        assertFalse(isChipTapSend(listOf(sendTap(2, 3), returnObject(13)), 0, 1))
        assertFalse(isChipTapSend(listOf(sendTap(1, 2), returnObject(13)), 0, 1))
        assertFalse(isChipTapSend(listOf(sendTap(3, 1), returnObject(13)), 0, 1))
        assertFalse(isChipTapSend(listOf(sendTap(3, 2, "b"), returnObject(13)), 0, 1))
        assertFalse(isChipTapSend(listOf(sendTap(3, 2, opcode = Opcode.INVOKE_INTERFACE), returnObject(13)), 0, 1))
        assertFalse(isChipTapSend(listOf(sendTap(3, 2), returnObject(12)), 0, 1))
        assertFalse(isChipTapSend(listOf(sendTap(3, 2), ImmutableInstruction11x(Opcode.THROW, 13)), 0, 1))
        assertFalse(isChipTapSend(listOf(sendTap(3, 2), returnVoid), 0, 1))
    }

    @Test
    fun `accepts the refusal of a greyed-out row that B1 goes in before, between the UBI hit and the play`() {
        assertTrue(isRefusedRowReturn(listOf(hit(0), returnObject(13), dataPool(6, 0)), 1))
    }

    @Test
    fun `refuses any other place for B1`() {
        val site = listOf(hit(0), returnObject(13), dataPool(6, 0))
        assertFalse(isRefusedRowReturn(site, 0))
        assertFalse(isRefusedRowReturn(site, 2))
        assertFalse(isRefusedRowReturn(listOf(returnObject(13), dataPool(6, 0)), 0))
        assertFalse(isRefusedRowReturn(listOf(hit(0), returnObject(13)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(1), returnObject(13), dataPool(6, 0)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0, "g"), returnObject(13), dataPool(6, 0)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0, opcode = Opcode.INVOKE_DIRECT), returnObject(13), dataPool(6, 0)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0), returnObject(12), dataPool(6, 0)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0), returnVoid, dataPool(6, 0)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0), ImmutableInstruction11x(Opcode.THROW, 13), dataPool(6, 0)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0), returnObject(13), dataPool(5, 0)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0), returnObject(13), dataPool(6, 1)), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0), returnObject(13),
            field(Opcode.IGET_OBJECT, 6, 0, "Lp/h6e;", "t", "Lcom/spotify/kodiak/dataloader/statement/DataStatement;")), 1))
        assertFalse(isRefusedRowReturn(listOf(hit(0), returnObject(13),
            field(Opcode.IPUT_OBJECT, 6, 0, "Lp/h6e;", "b", DATA_POOL)), 1))
    }

    @Test
    fun `accepts the entry of omni play that B2 goes in before, where this and the continuation are copied`() {
        assertTrue(isOmniPlayEntry(listOf(move(0, 17), move(1, 21)), 0))
    }

    @Test
    fun `refuses any other place for B2`() {
        val entry = listOf(move(0, 17), move(1, 21))
        assertFalse(isOmniPlayEntry(entry, 1))
        assertFalse(isOmniPlayEntry(entry, 2))
        assertFalse(isOmniPlayEntry(emptyList(), 0))
        assertFalse(isOmniPlayEntry(listOf(move(0, 17)), 0))
        assertFalse(isOmniPlayEntry(listOf(move(1, 21), move(0, 17)), 0))
        assertFalse(isOmniPlayEntry(listOf(move(2, 17), move(1, 21)), 0))
        assertFalse(isOmniPlayEntry(listOf(move(0, 18), move(1, 21)), 0))
        assertFalse(isOmniPlayEntry(listOf(move(0, 17), move(2, 21)), 0))
        assertFalse(isOmniPlayEntry(listOf(move(0, 17), move(1, 19)), 0), "the row, p2, rather than the continuation")
        assertFalse(isOmniPlayEntry(listOf(ImmutableInstruction22x(Opcode.MOVE_FROM16, 0, 17), move(1, 21)), 0))
        assertFalse(isOmniPlayEntry(listOf(move(0, 17), ImmutableInstruction32x(Opcode.MOVE_OBJECT_16, 1, 21)), 0))
    }

    @Test
    fun `accepts the entry of list play that L goes in before`() {
        assertTrue(isListPlayEntry(hitPart(2, 2)))
    }

    @Test
    fun `refuses any other instruction where L goes`() {
        assertFalse(isListPlayEntry(null))
        assertFalse(isListPlayEntry(hitPart(1, 2)))
        assertFalse(isListPlayEntry(hitPart(2, 1)))
        assertFalse(isListPlayEntry(field(Opcode.IGET_OBJECT, 2, 2, "Lp/mb40;", "b", "Lp/ja40;")))
        assertFalse(isListPlayEntry(field(Opcode.IGET_OBJECT, 2, 2, "Lp/mb41;", "a", "Lp/ja40;")))
        assertFalse(isListPlayEntry(field(Opcode.IPUT_OBJECT, 2, 2, "Lp/mb40;", "a", "Lp/ja40;")))
        assertFalse(isListPlayEntry(returnVoid))
    }

    /** `iget-object v[value], v[instance], Lp/mb40;->a:Lp/ja40;`, which `Lp/vy70;->d` starts with on its UBI hit, p2. */
    private fun hitPart(value: Int, instance: Int) = field(Opcode.IGET_OBJECT, value, instance, "Lp/mb40;", "a", "Lp/ja40;")

    /** `move-object/from16 v[to], v[from]`, as `Lp/h6e;->s` starts: this (v17), then the continuation (v21). */
    private fun move(to: Int, from: Int) = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, to, from)

    /** `invoke-virtual {v[list], v5, v3, v9}, Lp/h6e;->[name]`, the UBI hit `Lp/h6e;->r` logs for a refused row. */
    private fun hit(list: Int, name: String = "f", opcode: Opcode = Opcode.INVOKE_VIRTUAL) =
        ImmutableInstruction35c(opcode, 4, list, 5, 3, 9, 0,
            ImmutableMethodReference("Lp/h6e;", name, listOf("Ljava/lang/String;", "I", "Z"), "Lp/mb40;"))

    /** `iget-object v[value], v[list], Lp/h6e;->b`, where a playable row starts to play. */
    private fun dataPool(value: Int, list: Int) = field(Opcode.IGET_OBJECT, value, list, "Lp/h6e;", "b", DATA_POOL)

    /** `move-result-object v[register]`, which takes `Lp/xqw;->a`'s chips in Home's feed mapping. */
    private fun chips(register: Int) = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register)

    /** `new-instance v[register], ArrayList`, the copy of the chips that follows. */
    private fun copy(register: Int) = type(Opcode.NEW_INSTANCE, register, ARRAY_LIST)

    /** `invoke-virtual {v[loop], v[event]}, Lp/bay;->[name]`, which sends a chip tap's event to Home's loop. */
    private fun sendTap(loop: Int, event: Int, name: String = "invoke", opcode: Opcode = Opcode.INVOKE_VIRTUAL) =
        ImmutableInstruction35c(opcode, 2, loop, event, 0, 0, 0,
            ImmutableMethodReference("Lp/bay;", name, listOf("Ljava/lang/Object;"), "Ljava/lang/Object;"))

    /** `iput-object v[value], v[instance], Lp/sv70;->d`, the list menu's constructor storing its item providers. */
    private fun storeProviders(value: Int, instance: Int) = field(Opcode.IPUT_OBJECT, value, instance, "Lp/sv70;", "d", LIST)

    /** `iput-object v[value], v[instance], Lp/xkp;->i`, the shuffle button's constructor storing its button. */
    private fun storeButton(value: Int, instance: Int) = field(Opcode.IPUT_OBJECT, value, instance, "Lp/xkp;", "i", BUTTON)

    /** `iget-object v[register], v1, Provided;->items_`, v1 being `this` in the two-register getter. */
    private fun items(register: Int) = field(Opcode.IGET_OBJECT, register, 1, PROVIDED, "items_", "Lp/ih40;")

    private fun returnObject(register: Int) = ImmutableInstruction11x(Opcode.RETURN_OBJECT, register)

    private fun field(opcode: Opcode, value: Int, instance: Int, owner: String, name: String, type: String) =
        ImmutableInstruction22c(opcode, value, instance, ImmutableFieldReference(owner, name, type))

    private fun type(opcode: Opcode, register: Int, type: String) =
        ImmutableInstruction21c(opcode, register, ImmutableTypeReference(type))

    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)
    private val schedule = invoke(Opcode.INVOKE_VIRTUAL)

    private fun invoke(
        opcode: Opcode,
        name: String = "initializeScheduling",
        owner: String = "Lcom/spotify/cosmos/cosmosimpl/NativeRouter;",
    ) = ImmutableInstruction35c(opcode, 2, 3, 0, 0, 0, 0,
        ImmutableMethodReference(owner, name, listOf("Lcom/spotify/cosmos/cosmosimpl/Scheduler;"), "V"))
}
