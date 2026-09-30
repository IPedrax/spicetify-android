.class public final Lapp/spicetify/extension/spotify/extensions/nativebridge/UnavailableRowBridge;
.super Ljava/lang/Object;

# Hook B1 (unavailable songs report, section 2.4) for Unavailable songs. The default track row refuses a
# tap on a greyed-out song at Lp/h6e;->r 247, right after logging it. The hook hands over the list's tap
# handler, Lp/h6e;, whose e is the list's uri, and the row, Lp/wt70;: a its row id, c the song's uri, b()
# its restriction, a Lp/vbn0; enum, and f() whether it's banned. Hooks B2 and L hand over omni play's and
# list play's objects. UnavailableSongs decides; this only reads Spotify's objects.

# True goes on at 248, where a playable row starts to play, so Spotify tries the original. That path logs
# the tap's UBI hit a second time, after 246 logged it. False, or anything failing, keeps Spotify's
# refusal. Lp/vbn0; has no name() of its own, so Enum's is called. A failure goes to
# UnavailableSongs.bridgeFailed first, which puts it on the status line, since logcat is filtered on the
# test phone, and which catches its own Throwable.
.method public static onRefusedRow(Ljava/lang/Object;Ljava/lang/Object;)Z
    .locals 5
    :try_start
    check-cast p0, Lp/h6e;
    iget-object v0, p0, Lp/h6e;->e:Ljava/lang/String;
    check-cast p1, Lp/wt70;
    iget-object v1, p1, Lp/wt70;->c:Ljava/lang/String;
    iget-object v2, p1, Lp/wt70;->a:Ljava/lang/String;
    invoke-virtual {p1}, Lp/wt70;->b()Lp/vbn0;
    move-result-object v3
    invoke-virtual {v3}, Ljava/lang/Enum;->name()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {p1}, Lp/wt70;->f()Z
    move-result v4
    invoke-static {v0, v1, v2, v3, v4}, Lapp/spicetify/extension/spotify/extensions/UnavailableSongs;->onGreyedRowTap(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Z
    move-result v0
    return v0
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :failed

    :failed
    move-exception v0
    const-string v1, "onRefusedRow"
    invoke-static {v1, v0}, Lapp/spicetify/extension/spotify/extensions/UnavailableSongs;->bridgeFailed(Ljava/lang/String;Ljava/lang/Throwable;)V
    const-string v1, "Spicetify"
    const-string v2, "Couldn't answer a tap on a greyed-out song"
    invoke-static {v1, v2, v0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
    const/4 v0, 0x0
    return v0
.end method

# Hook B2 (unavailable songs report, sections 2.2 and 2.3). With the remote flag
# enable_omni_play_intent_handler on, Lp/h6e;->h sends every track row's tap to Lp/h6e;->s, omni play,
# and B1 never runs. B2 hands over the same two objects at s's entry, and this reads the same values plus
# g(), whether the row is playable, since omni play gets playable rows too. s is a coroutine, and each
# resume runs it again with a null row, which this skips. Nothing is returned: omni play goes on whatever
# happens here. A failure goes to UnavailableSongs.bridgeFailed first, as in onRefusedRow.
.method public static onOmniRow(Ljava/lang/Object;Ljava/lang/Object;)V
    .locals 6
    if-eqz p1, :done
    :try_start
    check-cast p0, Lp/h6e;
    iget-object v0, p0, Lp/h6e;->e:Ljava/lang/String;
    check-cast p1, Lp/wt70;
    iget-object v1, p1, Lp/wt70;->c:Ljava/lang/String;
    iget-object v2, p1, Lp/wt70;->a:Ljava/lang/String;
    invoke-virtual {p1}, Lp/wt70;->b()Lp/vbn0;
    move-result-object v3
    invoke-virtual {v3}, Ljava/lang/Enum;->name()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {p1}, Lp/wt70;->f()Z
    move-result v4
    invoke-virtual {p1}, Lp/wt70;->g()Z
    move-result v5
    invoke-static/range {v0 .. v5}, Lapp/spicetify/extension/spotify/extensions/UnavailableSongs;->onOmniRow(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :failed

    :done
    return-void

    :failed
    move-exception v0
    const-string v1, "onOmniRow"
    invoke-static {v1, v0}, Lapp/spicetify/extension/spotify/extensions/UnavailableSongs;->bridgeFailed(Ljava/lang/String;Ljava/lang/Throwable;)V
    const-string v1, "Spicetify"
    const-string v2, "Couldn't follow a tap on a greyed-out song"
    invoke-static {v1, v2, v0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
    return-void
.end method

# Hook L. On the phone, a tap on a greyed-out song passes Lp/h6e;->r's check, since its row reads as
# playable, and goes on into list play, Lp/vy70;->d, whose skip to the row the core refuses with code 22.
# L hands over list play's this, Lp/vy70;, whose a is the list's uri (c passes it to Lp/cp60;->E as the
# context of PlaylistPlayService/Play), and its request, Lp/ly70;. A Lp/jy70; skips to a row, and its a
# is the row id (cp60.E sets it as skip_to's track_uid); a Lp/ky70; skips to a song, which passes null.
# Every list play comes here, so this only reads and hands over. A failure goes to
# UnavailableSongs.bridgeFailed first, as in onRefusedRow, and list play goes on whatever happens.
.method public static onListPlay(Ljava/lang/Object;Ljava/lang/Object;)V
    .locals 3
    :try_start
    check-cast p0, Lp/vy70;
    iget-object v0, p0, Lp/vy70;->a:Ljava/lang/String;
    const/4 v1, 0x0
    instance-of v2, p1, Lp/jy70;
    if-eqz v2, :tell
    check-cast p1, Lp/jy70;
    iget-object v1, p1, Lp/jy70;->a:Ljava/lang/String;
    :tell
    invoke-static {v0, v1}, Lapp/spicetify/extension/spotify/extensions/UnavailableSongs;->onListPlay(Ljava/lang/String;Ljava/lang/String;)V
    :try_end
    .catch Ljava/lang/Throwable; {:try_start .. :try_end} :failed
    return-void

    :failed
    move-exception v0
    const-string v1, "onListPlay"
    invoke-static {v1, v0}, Lapp/spicetify/extension/spotify/extensions/UnavailableSongs;->bridgeFailed(Ljava/lang/String;Ljava/lang/Throwable;)V
    const-string v1, "Spicetify"
    const-string v2, "Couldn't follow a list play"
    invoke-static {v1, v2, v0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
    return-void
.end method
