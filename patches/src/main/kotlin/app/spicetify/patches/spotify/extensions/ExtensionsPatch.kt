package app.spicetify.patches.spotify.extensions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.spicetify.patches.spotify.settings.NativeSettingsAbi
import app.spicetify.patches.spotify.settings.enableSetting
import app.spicetify.patches.spotify.settings.settingsPatch
import app.spicetify.patches.spotify.spotifyCompatibility
import app.spicetify.patches.spotify.theme.themePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import java.util.Properties

private const val COSMOS_SERVICE = "Lcom/spotify/cosmos/sharedcosmosrouterservice/SharedCosmosRouterService;"
private const val MENU_BRIDGE = "Lapp/spicetify/extension/spotify/extensions/nativebridge/MenuBridge;"
private const val HIDE_PODCASTS = "Lapp/spicetify/extension/spotify/extensions/HidePodcasts;"
private const val SECTION = "Lcom/spotify/casita/v1/resolved/Section;"
private const val PROVIDED = "Lcom/spotify/casita/v1/resolved/Provided;"
private const val SHUFFLE_BUTTON = "Lp/xkp;"
private const val LIST_MENU = "Lp/sv70;"
private const val PLAYLIST_MENU_PROVIDER =
    "Lapp/spicetify/extension/spotify/extensions/nativebridge/PlaylistMenuProvider;"
private const val HOME_FEEDS = "Lp/qrl;"
private const val CHIP_EVENTS = "Lp/a4v;"
private const val HOME_CHIP_BRIDGE = "Lapp/spicetify/extension/spotify/extensions/nativebridge/HomeChipBridge;"
private const val TRACK_ROW_TAPS = "Lp/h6e;"
private const val UNAVAILABLE_ROW_BRIDGE =
    "Lapp/spicetify/extension/spotify/extensions/nativebridge/UnavailableRowBridge;"
private const val LIST_PLAY = "Lp/vy70;"

// Every protobuf field number the extension's Esperanto.java writes or reads, as class#NAME_FIELD_NUMBER,
// for the extensions and for the Home shortcuts picker, which reads Your Library's names and covers.
// These classes keep their names and constants, so a build that renumbers a field fails here. Not covered:
// map entries (1 and 2 by protobuf's rules), and enum values, since obfuscated enums have no constants:
// BoolPredicate, and Your Library's Filter (PLAYLIST 2, ALBUM 0) and LinkType (TRACK 4).
internal val esperantoFieldNumbers = mapOf(
    "Lcom/spotify/player/esperanto/proto/EsContextPlayerState\$ContextPlayerState;" to mapOf(
        "CONTEXT_URI" to 2, "TRACK" to 7, "PLAYBACK_ID" to 8, "IS_PLAYING" to 13, "IS_PAUSED" to 14,
        "NEXT_TRACKS" to 21, "QUEUE_REVISION" to 25,
    ),
    "Lcom/spotify/player/esperanto/proto/EsGetStateRequest\$GetStateRequest;" to
        mapOf("PREV_TRACKS_CAP" to 1, "NEXT_TRACKS_CAP" to 2),
    "Lcom/spotify/player/esperanto/proto/EsContextPlayerError\$ContextPlayerError;" to
        mapOf("CODE" to 1, "MESSAGE" to 2, "DATA" to 3),
    "Lcom/spotify/player/esperanto/proto/EsProvidedTrack\$ProvidedTrack;" to mapOf("CONTEXT_TRACK" to 1, "PROVIDER" to 4),
    "Lcom/spotify/player/esperanto/proto/EsContextTrack\$ContextTrack;" to mapOf("URI" to 1, "UID" to 2, "METADATA" to 3),
    "Lcom/spotify/player/esperanto/proto/EsPlay\$PlayRequest;" to mapOf("PREPARE_PLAY_REQUEST" to 1),
    "Lcom/spotify/player/esperanto/proto/EsPreparePlay\$PreparePlayRequest;" to mapOf("CONTEXT" to 1, "OPTIONS" to 2),
    "Lcom/spotify/player/esperanto/proto/EsContext\$Context;" to mapOf("PAGES" to 1, "URI" to 3, "URL" to 4),
    "Lcom/spotify/player/esperanto/proto/EsContextPage\$ContextPage;" to mapOf("TRACKS" to 1),
    "Lcom/spotify/player/esperanto/proto/EsPreparePlayOptions\$PreparePlayOptions;" to
        mapOf("SKIP_TO" to 3, "PLAYER_OPTIONS_OVERRIDE" to 7),
    "Lcom/spotify/player/esperanto/proto/EsSkipToTrack\$SkipToTrack;" to
        mapOf("TRACK_UID" to 3, "TRACK_URI" to 4, "TRACK_INDEX" to 5),
    "Lcom/spotify/player/esperanto/proto/EsOptional\$OptionalInt64;" to mapOf("VALUE" to 1),
    "Lcom/spotify/player/esperanto/proto/EsContextPlayerOptions\$ContextPlayerOptionOverrides;" to
        mapOf("SHUFFLING_CONTEXT" to 1),
    "Lcom/spotify/player/esperanto/proto/EsOptional\$OptionalBoolean;" to mapOf("VALUE" to 1),
    "Lcom/spotify/player/esperanto/proto/EsSetShufflingContext\$SetShufflingContextRequest;" to
        mapOf("SHUFFLING_CONTEXT" to 1),
    "Lcom/spotify/player/esperanto/proto/EsAddToQueueRequest\$AddToQueueRequest;" to mapOf("TRACK" to 1),
    "Lcom/spotify/player/esperanto/proto/EsPlayAsNextInQueueRequest\$PlayAsNextInQueueRequest;" to mapOf("TRACKS" to 1),
    "Lcom/spotify/player/esperanto/proto/EsSetQueueRequest\$SetQueueRequest;" to
        mapOf("NEXT_TRACKS" to 1, "QUEUE_REVISION" to 3),
    "Lcom/spotify/player/esperanto/proto/EsResponseWithReasons\$ResponseWithReasons;" to mapOf("ERROR" to 1),
    "Lspotify/playlist/esperanto/proto/PlaylistGetRequest;" to mapOf("URI" to 1, "QUERY" to 2, "POLICY" to 3),
    "Lspotify/playlist/esperanto/proto/PlaylistQuery;" to
        mapOf("BOOL_PREDICATES" to 1, "RANGE" to 4, "SHOW_UNAVAILABLE" to 8),
    "Lspotify/playlist/esperanto/proto/PlaylistRange;" to mapOf("START" to 1, "LENGTH" to 2),
    "Lcom/spotify/playlist/policy/proto/PlaylistRequestDecorationPolicy;" to
        mapOf("PLAYLIST" to 1, "TRACK" to 2, "ITEM" to 4),
    "Lcom/spotify/playlist/policy/proto/PlaylistDecorationPolicy;" to mapOf("UNRANGED_LENGTH" to 49),
    "Lcom/spotify/playlist/policy/proto/PlaylistTrackDecorationPolicy;" to mapOf("TRACK" to 1),
    "Lcom/spotify/cosmos/util/policy/proto/TrackDecorationPolicy;" to mapOf("PLAYABLE" to 5, "IS_LOCAL" to 13),
    "Lcom/spotify/playlist/policy/proto/PlaylistItemDecorationPolicy;" to mapOf("URI" to 1, "ROW_ID" to 9),
    "Lspotify/playlist/esperanto/proto/PlaylistGetResponse;" to mapOf("STATUS" to 1, "DATA" to 2),
    "Lspotify/playlist/esperanto/proto/ResponseStatus;" to mapOf("STATUS_CODE" to 1),
    "Lcom/spotify/playlist/proto/PlaylistRequest\$Response;" to
        mapOf("ITEM" to 1, "UNRANGED_LENGTH" to 4, "LOADING_CONTENTS" to 6),
    "Lcom/spotify/playlist/proto/PlaylistRequest\$Item;" to
        mapOf("TRACK_METADATA" to 4, "ROW_ID" to 7, "TRACK_PLAY_STATE" to 8, "URI" to 18),
    "Lcom/spotify/cosmos/util/proto/TrackMetadata;" to mapOf("PLAYABLE" to 6, "IS_LOCAL" to 11),
    "Lcom/spotify/cosmos/util/proto/TrackPlayState;" to mapOf("IS_PLAYABLE" to 1, "PLAYABILITY_RESTRICTION" to 2),
    "Lcom/spotify/settings/esperanto/proto/SettingsOuterClass\$SettingsState;" to mapOf("SHOW_UNAVAILABLE_TRACKS" to 17),
    "Lcom/spotify/metadata/esperanto/proto/GetEntityRequest;" to mapOf("URI" to 1),
    "Lcom/spotify/metadata/esperanto/proto/GetEntityResponse;" to mapOf("ITEM" to 1),
    "Lcom/spotify/metadata/cosmos/proto/MetadataCosmos\$MetadataItem;" to mapOf("ERROR" to 1, "ALBUM" to 3, "TRACK" to 4),
    "Lcom/spotify/metadata/proto/Metadata\$Album;" to mapOf("DISC" to 11),
    "Lcom/spotify/metadata/proto/Metadata\$Disc;" to mapOf("TRACK" to 3),
    "Lcom/spotify/metadata/proto/Metadata\$Track;" to mapOf(
        "GID" to 1, "NAME" to 2, "ARTIST" to 4, "DURATION" to 7, "EXPLICIT" to 9, "EXTERNAL_ID" to 10,
        "ALTERNATIVE" to 13,
    ),
    "Lcom/spotify/metadata/proto/Metadata\$Artist;" to mapOf("GID" to 1, "NAME" to 2),
    "Lcom/spotify/metadata/proto/Metadata\$ExternalId;" to mapOf("TYPE" to 1, "ID" to 2),
    "Lspotify/your_library/esperanto/proto/YourLibraryRequest;" to mapOf("HEADER" to 1),
    "Lspotify/your_library/esperanto/proto/YourLibraryRequestHeader;" to mapOf(
        "LENGTH" to 12, "FILTERS" to 14, "ALL_PLAYLISTS" to 17, "NUM_LINK_TYPES_IN_PLAYLISTS" to 25,
        "IGNORE_PINNING" to 26,
    ),
    "Lspotify/your_library/proto/YourLibraryConfig\$YourLibraryFilters;" to mapOf("FILTER" to 1),
    "Lspotify/your_library/esperanto/proto/YourLibraryResponse;" to
        mapOf("HEADER" to 1, "ENTITY" to 2, "PINNED_ENTITY" to 3, "STATUS_CODE" to 98, "ERROR" to 99),
    "Lspotify/your_library/esperanto/proto/YourLibraryResponseHeader;" to mapOf("IS_LOADING" to 12),
    "Lspotify/your_library/proto/YourLibraryDecoratedEntityOuterClass\$YourLibraryDecoratedEntity;" to
        mapOf("ENTITY_INFO" to 1, "ALBUM" to 2, "PLAYLIST" to 4),
    "Lspotify/your_library/proto/YourLibraryDecoratedEntityOuterClass\$YourLibraryEntityInfo;" to
        mapOf("NAME" to 2, "URI" to 3, "IMAGE_URI" to 6),
    "Lspotify/your_library/proto/YourLibraryDecoratedEntityOuterClass\$YourLibraryPlaylistExtraInfo;" to
        mapOf("NUMBER_OF_ITEMS_PER_LINK_TYPE" to 12),
    "Lspotify/your_library/proto/YourLibraryDecoratedEntityOuterClass\$NumberOfItemsForLinkType;" to
        mapOf("LINK_TYPE" to 1, "NUM_ITEMS" to 2),
).flatMap { (type, fields) -> fields.map { (name, number) -> "$type#${name}_FIELD_NUMBER" to number } }.toMap()

/**
 * H1 and the protocol check, shared by every patch that talks to Spotify's core through the player
 * bridge: the extensions, and Home pins, whose picker reads Your Library through it. It has no name,
 * so Manager never lists it. It leaves InstalledPatches.extensions() off, since only the extensions
 * patch turns that on, and without it the bridge starts no extension.
 */
internal val playerBridgePatch = bytecodePatch {
    dependsOn(settingsPatch) // H1 calls into the extension, which the settings patch merges

    execute {
        val mismatches = fieldNumberMismatches(esperantoFieldNumbers) { key ->
            val (type, name) = key.split('#')
            val constant = classDefByOrNull(type)?.staticFields?.firstOrNull { it.name == name }
            (constant?.initialValue as? IntEncodedValue)?.value
        }
        if (mismatches.isNotEmpty()) throw PatchException("Spotify extensions protocol changed: $mismatches")

        // H1: hand each new service to the player bridge, which sends through its router.
        val constructor = mutableClassDefBy(COSMOS_SERVICE).methods.single {
            it.name == "<init>" &&
                it.parameterTypes.getOrNull(1) == "Lcom/spotify/cosmos/servicebasedrouter/RemoteNativeRouter;"
        }
        val index = bridgeHookIndex(constructor.implementation!!.instructions)
            ?: throw PatchException("Spotify extensions ABI changed: $COSMOS_SERVICE. Use the verified Spotify 9.1.80.2221 APK.")
        constructor.addInstructions(index,
            "invoke-static/range {p0 .. p0}, Lapp/spicetify/extension/spotify/extensions/PlayerBridge;->onCosmos(Ljava/lang/Object;)V")
    }
}

@Suppress("unused")
val extensionsPatch = bytecodePatch(
    name = "Spicetify extensions",
    description = "Adds Android versions of Spicetify extensions to the Spicetify Marketplace: " +
        "Trash Bin, Play a random song, Shuffle+, Hide podcasts and Unavailable songs. " +
        "Turn each one on in the Marketplace.",
    default = false,
) {
    compatibleWith(spotifyCompatibility)
    dependsOn(themePatch, playerBridgePatch)

    execute {
        val snapshot = Properties().apply {
            NativeSettingsAbi::class.java.getResourceAsStream("/extensions/9.1.80.2221.properties")!!.use(::load)
        }
        for (type in snapshot.stringPropertyNames()) {
            val definition = classDefByOrNull(type)
                ?: throw PatchException("Spotify extensions ABI changed: missing $type")
            if (NativeSettingsAbi.digest(definition) != snapshot.getProperty(type)) {
                throw PatchException("Spotify extensions ABI changed: $type. Use the verified Spotify 9.1.80.2221 APK.")
            }
        }

        // T1 and T2 (report 4.1 and 4.3): the menu bridge gets each context menu's frozen item list,
        // with its CollectionTrack (v11) or CollectionArtist (v22), right before the menu model is
        // built from it. v1 is dead until that new-instance, so T2 moves v22 there for invoke-static.
        val trackMenu = menuBuilder("Lp/b9p0;", 1678)
        val artistMenu = menuBuilder("Lp/lr5;", 633)

        // P1 to P6 (report 5.1 to 5.3, checked in research/2026-09-29-hide-podcasts-check.md): Hide podcasts'
        // filters. Each index must hold the instruction the check found there, so a wrong index refuses.
        val homeSection = hookSite("Lp/mz1;", "g0", listOf(SECTION), 0,
            Opcode.INVOKE_VIRTUAL, "Lp/mz1;->O($SECTION)Lp/n920;")
        val homeItems = mutableClassDefBy(PROVIDED).methods.single { it.name == "getItemsList" }
        if (!isItemsGetter(homeItems.implementation!!.instructions)) {
            throw PatchException("Spotify extensions ABI changed: $PROVIDED. Use the verified Spotify 9.1.80.2221 APK.")
        }
        val homeChips = hookSite("Lp/xqw;", "a", listOf("Ljava/util/List;"), 0, Opcode.NEW_INSTANCE, "Ljava/util/ArrayList;")
        val searchEntity = hookSite("Lp/bzw0;", "b", listOf("Lcom/spotify/searchview/proto/Entity;"), 0,
            Opcode.MOVE_OBJECT_FROM16)
        val searchChips = hookSite("Lp/ipy;", "<init>", listOf("Ljava/util/ArrayList;"), 1,
            Opcode.IPUT_OBJECT, "Lp/ipy;->a:Ljava/util/ArrayList;")
        val libraryChips = hookSite("Lp/j290;", "<init>",
            listOf("I", "Lp/m740;", "Ljava/util/ArrayList;", "Ljava/util/List;", "Ljava/util/List;", "Z", "I"), 1,
            Opcode.IPUT, "Lp/j290;->a:I")

        // N1 (entry-points report 2.3): Now Playing's shuffle button gets its long-press at the end of its
        // constructor, where v2 still holds the button that index 61 stored.
        val shuffleButton = mutableClassDefBy(SHUFFLE_BUTTON).methods.single {
            it.name == "<init>" && it.parameterTypes == listOf("Landroid/content/Context;")
        }
        if (!isShuffleButtonEnd(shuffleButton.implementation!!.instructions, 62)) {
            throw PatchException("Spotify extensions ABI changed: $SHUFFLE_BUTTON. Use the verified Spotify 9.1.80.2221 APK.")
        }

        // M1 (entry-points report 3.5): the list menu, which playlists and Liked Songs open, gets one more
        // item provider. v4 holds the providers until index 4 stores them.
        val listMenu = mutableClassDefBy(LIST_MENU).methods.single {
            it.name == "<init>" &&
                it.parameterTypes == listOf("Lp/y3w0;", "Lp/vz1;", "Ljava/util/List;", "Ljava/util/List;", "Lp/a94;")
        }
        if (!isItemProvidersStore(listMenu.implementation!!.instructions.getOrNull(4))) {
            throw PatchException("Spotify extensions ABI changed: $LIST_MENU. Use the verified Spotify 9.1.80.2221 APK.")
        }

        // A and B (entry-points report 1.4): Home's filter row gets the Random pill once xqw.a has
        // rewritten the server's chips, which v1 holds until 316 copies them. Each chip tap is offered
        // to the bridge before its q8w reaches Home's loop at 1205; v1, the chip's id, is dead after
        // 1204, so it takes the answer, and a handled tap skips to the case's return at 1214.
        val homeFeeds = mutableClassDefBy(HOME_FEEDS).methods.single {
            it.name == "invokeSuspend" && it.parameterTypes == listOf("Ljava/lang/Object;")
        }
        if (!isChipsCopy(homeFeeds.implementation!!.instructions, 315)) {
            throw PatchException("Spotify extensions ABI changed: $HOME_FEEDS. Use the verified Spotify 9.1.80.2221 APK.")
        }
        val chipEvents = mutableClassDefBy(CHIP_EVENTS).methods.single {
            it.name == "invoke" && it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
        }
        if (!isChipTapSend(chipEvents.implementation!!.instructions, 1205, 1214)) {
            throw PatchException("Spotify extensions ABI changed: $CHIP_EVENTS. Use the verified Spotify 9.1.80.2221 APK.")
        }

        // B1 (unavailable report, section 2.4): the default track row refuses a tap on a greyed-out song
        // with the return at 247, right after its UBI hit at 246. The bridge gets the list's tap handler (v0,
        // this) and the row (v11), and a true answer goes on at 248, where a playable row starts to play. That
        // path logs the tap's UBI hit again, so a greyed tap that goes on logs two. v6 takes the answer, since
        // 248 writes it before anything reads it.
        val rowTap = mutableClassDefBy(TRACK_ROW_TAPS).methods.single {
            it.name == "r" && it.parameterTypes == listOf("I", "Lp/wt70;", "Lp/lv40;", "Lp/ivj;")
        }
        if (!isRefusedRowReturn(rowTap.implementation!!.instructions, 247)) {
            throw PatchException("Spotify extensions ABI changed: $TRACK_ROW_TAPS. Use the verified Spotify 9.1.80.2221 APK.")
        }
        // B2 (unavailable report, sections 2.2 and 2.3): with the remote flag enable_omni_play_intent_handler
        // on, h6e.h sends every track row's tap to s, omni play, and never to r, so B1 never runs. B2 hands
        // the bridge s's this (p0) and row (p2) at its entry, which no branch targets and no try block covers.
        // v0 and v1 are free there, since 0 and 1 write them next. s is a coroutine, and each resume runs it
        // again with a null row.
        val omniTap = mutableClassDefBy(TRACK_ROW_TAPS).methods.single {
            it.name == "s" && it.parameterTypes == listOf("I", "Lp/wt70;", "Lp/mv40;", "Lp/ivj;")
        }
        if (!isOmniPlayEntry(omniTap.implementation!!.instructions, 0)) {
            throw PatchException("Spotify extensions ABI changed: $TRACK_ROW_TAPS. Use the verified Spotify 9.1.80.2221 APK.")
        }
        // L: on the phone, a tap on a greyed-out song passes r's check, since its row reads as playable, so B1
        // never runs. r goes on into list play, d, whose skip to the row the core refuses with code 22. L hands
        // the bridge d's this (p0) and request (p1) at its entry, which no branch targets and no try block
        // covers, for every list play. It writes no register, so the 4 registers stay.
        val listPlay = mutableClassDefBy(LIST_PLAY).methods.single {
            it.name == "d" && it.parameterTypes == listOf("Lp/ly70;", "Lp/mb40;", "Lp/fvj;")
        }
        if (!isListPlayEntry(listPlay.implementation!!.instructions.getOrNull(0))) {
            throw PatchException("Spotify extensions ABI changed: $LIST_PLAY. Use the verified Spotify 9.1.80.2221 APK.")
        }

        trackMenu.addInstructions(1678, """
            invoke-static {v0, v11}, $MENU_BRIDGE->track(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
            move-result-object v0
        """.trimIndent())
        artistMenu.addInstructions(633, """
            move-object/from16 v1, v22
            invoke-static {v0, v1}, $MENU_BRIDGE->artist(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
            move-result-object v0
        """.trimIndent())

        // P1 and P4 return null for a dropped section or result, which every caller already skips. v0 is
        // free at both: g0 writes it at index 1 or in its catch handler before any read, and b's index 0
        // writes it. The code lands before g0's try block, which starts at the original index 0.
        homeSection.addInstructionsWithLabels(0, """
            invoke-static {p1}, $HIDE_PODCASTS->hideHomeSection(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :keep
            const/4 v0, 0x0
            return-object v0
        """.trimIndent(), ExternalLabel("keep", homeSection.getInstruction(0)))
        homeItems.addInstructions(1, """
            invoke-static {v0}, $HIDE_PODCASTS->filterHomeItems(Ljava/util/List;)Ljava/util/List;
            move-result-object v0
        """.trimIndent())
        homeChips.addInstructions(0, """
            invoke-static {p0}, $HIDE_PODCASTS->filterHomeChips(Ljava/util/List;)Ljava/util/List;
            move-result-object p0
        """.trimIndent())
        searchEntity.addInstructionsWithLabels(0, """
            invoke-static/range {p1 .. p1}, $HIDE_PODCASTS->hideSearchEntity(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :keep
            const/4 v0, 0x0
            return-object v0
        """.trimIndent(), ExternalLabel("keep", searchEntity.getInstruction(0)))
        // The constructors' lists are filtered right after Object.<init>, before they're stored.
        searchChips.addInstructions(1, """
            invoke-static {p1}, $HIDE_PODCASTS->filterSearchChips(Ljava/util/ArrayList;)Ljava/util/ArrayList;
            move-result-object p1
        """.trimIndent())
        libraryChips.addInstructions(1, """
            invoke-static {p5}, $HIDE_PODCASTS->filterLibraryChips(Ljava/util/List;)Ljava/util/List;
            move-result-object p5
        """.trimIndent())
        shuffleButton.addInstructions(62,
            "invoke-static {v2}, Lapp/spicetify/extension/spotify/extensions/NowPlayingShuffle;->onButton(Landroid/view/View;)V")
        listMenu.addInstructions(4, """
            invoke-static {v4}, $PLAYLIST_MENU_PROVIDER->providers(Ljava/util/List;)Ljava/util/List;
            move-result-object v4
        """.trimIndent())
        homeFeeds.addInstructions(315, """
            invoke-static {v1}, $HOME_CHIP_BRIDGE->chips(Ljava/util/List;)Ljava/util/List;
            move-result-object v1
        """.trimIndent())
        chipEvents.addInstructionsWithLabels(1205, """
            invoke-static {v1}, $HOME_CHIP_BRIDGE->onTap(Ljava/lang/String;)Z
            move-result v1
            if-nez v1, :handled
        """.trimIndent(), ExternalLabel("handled", chipEvents.getInstruction(1214)))
        rowTap.addInstructionsWithLabels(247, """
            invoke-static {v0, v11}, $UNAVAILABLE_ROW_BRIDGE->onRefusedRow(Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v6
            if-eqz v6, :refuse
            goto :play
        """.trimIndent(),
            ExternalLabel("refuse", rowTap.getInstruction(247)), ExternalLabel("play", rowTap.getInstruction(248)))
        omniTap.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p2
            invoke-static {v0, v1}, $UNAVAILABLE_ROW_BRIDGE->onOmniRow(Ljava/lang/Object;Ljava/lang/Object;)V
        """.trimIndent())
        listPlay.addInstructions(0,
            "invoke-static {p0, p1}, $UNAVAILABLE_ROW_BRIDGE->onListPlay(Ljava/lang/Object;Ljava/lang/Object;)V")
        enableSetting("extensions")
    }
}

/** Menu builder [type]'s `apply(Object)`, once [index] holds the menu model's new-instance. Throws otherwise. */
private fun BytecodePatchContext.menuBuilder(type: String, index: Int): MutableMethod {
    val apply = mutableClassDefBy(type).methods.single {
        it.name == "apply" && it.parameterTypes == listOf("Ljava/lang/Object;")
    }
    if (!isMenuModel(apply.implementation!!.instructions.getOrNull(index))) {
        throw PatchException("Spotify extensions ABI changed: $type. Use the verified Spotify 9.1.80.2221 APK.")
    }
    return apply
}

/** [type]'s method [name]([parameters]), once the instruction at [index] is [opcode] with [reference]. Throws otherwise. */
private fun BytecodePatchContext.hookSite(
    type: String,
    name: String,
    parameters: List<String>,
    index: Int,
    opcode: Opcode,
    reference: String? = null,
): MutableMethod {
    val method = mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes == parameters }
    if (!isHookSite(method.implementation!!.instructions.getOrNull(index), opcode, reference)) {
        throw PatchException("Spotify extensions ABI changed: $type. Use the verified Spotify 9.1.80.2221 APK.")
    }
    return method
}

/** Whether [instruction] is [opcode] with [reference], written like `Lp/a;->b:I`, or with none when it's null. */
internal fun isHookSite(instruction: Instruction?, opcode: Opcode, reference: String? = null): Boolean =
    instruction?.opcode == opcode && (instruction as? ReferenceInstruction)?.reference?.toString() == reference

/** Whether [instructions] are the whole of P2's getter: `iget-object v0, v1, Provided;->items_`, then `return-object v0`. */
internal fun isItemsGetter(instructions: List<Instruction>): Boolean =
    instructions.size == 2 && isHookSite(instructions[0], Opcode.IGET_OBJECT, "$PROVIDED->items_:Lp/ih40;") &&
        isHookSite(instructions[1], Opcode.RETURN_OBJECT) &&
        instructions.all { (it as OneRegisterInstruction).registerA == 0 }

/**
 * Whether [index] holds N1's place in the shuffle button's constructor: `return-void`, right after
 * `iput-object v2, v4, Lp/xkp;->i`, which stores the button that N1 passes on from v2.
 */
internal fun isShuffleButtonEnd(instructions: List<Instruction>, index: Int): Boolean {
    val store = instructions.getOrNull(index - 1)
    return isHookSite(store, Opcode.IPUT_OBJECT, "$SHUFFLE_BUTTON->i:Landroidx/appcompat/widget/AppCompatImageButton;") &&
        (store as TwoRegisterInstruction).registerA == 2 && store.registerB == 4 &&
        isHookSite(instructions.getOrNull(index), Opcode.RETURN_VOID)
}

/**
 * Whether [instruction] is `iput-object v4, v0, Lp/sv70;->d`, the list menu's constructor storing its
 * item providers, which M1 replaces in v4 first.
 */
internal fun isItemProvidersStore(instruction: Instruction?): Boolean =
    isHookSite(instruction, Opcode.IPUT_OBJECT, "$LIST_MENU->d:Ljava/util/List;") &&
        (instruction as TwoRegisterInstruction).registerA == 4 && instruction.registerB == 0

/**
 * Whether [index] holds A's place in `Lp/qrl;->invokeSuspend`: `new-instance v2, ArrayList`, which copies
 * the chips, right after `move-result-object v1` took them from `Lp/xqw;->a`. A replaces them in v1 first.
 */
internal fun isChipsCopy(instructions: List<Instruction>, index: Int): Boolean {
    val chips = instructions.getOrNull(index - 1)
    val copy = instructions.getOrNull(index)
    return isHookSite(chips, Opcode.MOVE_RESULT_OBJECT) && (chips as OneRegisterInstruction).registerA == 1 &&
        isHookSite(copy, Opcode.NEW_INSTANCE, "Ljava/util/ArrayList;") && (copy as OneRegisterInstruction).registerA == 2
}

/**
 * Whether [index] holds B's place in `Lp/a4v;->invoke`: `invoke-virtual {v3, v2}, Lp/bay;->invoke`, which
 * sends a chip tap's `Lp/q8w;` to Home's loop, and [end] holds the `return-object v13` that B skips to.
 */
internal fun isChipTapSend(instructions: List<Instruction>, index: Int, end: Int): Boolean {
    val send = instructions.getOrNull(index)
    val done = instructions.getOrNull(end)
    return isHookSite(send, Opcode.INVOKE_VIRTUAL, "Lp/bay;->invoke(Ljava/lang/Object;)Ljava/lang/Object;") &&
        (send as FiveRegisterInstruction).registerC == 3 && send.registerD == 2 &&
        isHookSite(done, Opcode.RETURN_OBJECT) && (done as OneRegisterInstruction).registerA == 13
}

/**
 * Whether [index] holds B1's place in `Lp/h6e;->r`: `return-object v13`, the refusal of a greyed-out row,
 * right after `invoke-virtual {v0, v5, v3, v9}, Lp/h6e;->f`, its UBI hit on this (v0), and right before
 * `iget-object v6, v0, Lp/h6e;->b`, where a playable row starts to play, writing v6 first.
 */
internal fun isRefusedRowReturn(instructions: List<Instruction>, index: Int): Boolean {
    val hit = instructions.getOrNull(index - 1)
    val refuse = instructions.getOrNull(index)
    val play = instructions.getOrNull(index + 1)
    return isHookSite(hit, Opcode.INVOKE_VIRTUAL, "$TRACK_ROW_TAPS->f(Ljava/lang/String;IZ)Lp/mb40;") &&
        (hit as FiveRegisterInstruction).registerC == 0 &&
        isHookSite(refuse, Opcode.RETURN_OBJECT) && (refuse as OneRegisterInstruction).registerA == 13 &&
        isHookSite(play, Opcode.IGET_OBJECT, "$TRACK_ROW_TAPS->b:Lcom/spotify/kodiak/dataloader/DataPool;") &&
        (play as TwoRegisterInstruction).registerA == 6 && play.registerB == 0
}

/**
 * Whether [index] holds B2's place at the entry of `Lp/h6e;->s` (22 registers): `move-object/from16 v0,
 * v17`, which copies this (p0), then `move-object/from16 v1, v21`, the continuation (p4). B2 goes in
 * before them, so they write v0 and v1 right after it, before anything reads either.
 */
internal fun isOmniPlayEntry(instructions: List<Instruction>, index: Int): Boolean {
    val self = instructions.getOrNull(index)
    val continuation = instructions.getOrNull(index + 1)
    return isHookSite(self, Opcode.MOVE_OBJECT_FROM16) && (self as TwoRegisterInstruction).registerA == 0 &&
        self.registerB == 17 && isHookSite(continuation, Opcode.MOVE_OBJECT_FROM16) &&
        (continuation as TwoRegisterInstruction).registerA == 1 && continuation.registerB == 21
}

/**
 * Whether [instruction] is `iget-object v2, v2, Lp/mb40;->a:Lp/ja40;`, the first instruction of `Lp/vy70;->d`
 * (4 registers, so this is v0 and its request v1), which L goes in before.
 */
internal fun isListPlayEntry(instruction: Instruction?): Boolean =
    isHookSite(instruction, Opcode.IGET_OBJECT, "Lp/mb40;->a:Lp/ja40;") &&
        (instruction as TwoRegisterInstruction).registerA == 2 && instruction.registerB == 2

/** Whether [instruction] is `new-instance v1, Lp/krj;`, the menu model that T1 and T2 insert before. */
internal fun isMenuModel(instruction: Instruction?): Boolean =
    instruction?.opcode == Opcode.NEW_INSTANCE && (instruction as OneRegisterInstruction).registerA == 1 &&
        ((instruction as ReferenceInstruction).reference as TypeReference).type == "Lp/krj;"

/** Each expected `class#FIELD_NUMBER` whose value in the APK differs, or that the APK lacks. */
internal fun fieldNumberMismatches(expected: Map<String, Int>, actual: (String) -> Int?): List<String> =
    expected.mapNotNull { (key, number) ->
        when (val found = actual(key)) {
            number -> null
            null -> "$key missing"
            else -> "$key expected $number, found $found"
        }
    }

/**
 * H1's place in `SharedCosmosRouterService.<init>`: before its only return-void, which must directly follow
 * `NativeRouter.initializeScheduling`, so the router already takes requests. Null for any other shape.
 */
internal fun bridgeHookIndex(instructions: List<Instruction>): Int? {
    val index = instructions.indices.singleOrNull { instructions[it].opcode == Opcode.RETURN_VOID } ?: return null
    val call = instructions.getOrNull(index - 1)
    val method = (call as? ReferenceInstruction)?.reference as? MethodReference
    return index.takeIf {
        call?.opcode == Opcode.INVOKE_VIRTUAL && method?.name == "initializeScheduling" &&
            method.definingClass == "Lcom/spotify/cosmos/cosmosimpl/NativeRouter;"
    }
}
