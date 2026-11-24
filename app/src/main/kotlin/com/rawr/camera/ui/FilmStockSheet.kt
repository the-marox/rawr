package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.settings.model.FilmStocks

/*
 * Rawr 17U fork: the film stock picker as cards in groups (negative, cinema, slide, print paper), after the film
 * browser of the fork's own Klipp Lab (utalpt/klipp, FilmBrowser17u). Tap a card to use the stock; hold it to see
 * it on the live viewfinder while the finger is down (the setting is not changed), release to go back.
 * The cards show the stock's name for now; previews of the current frame through each stock come later.
 */

/** One card: a position in [FilmStocks.films] or [FilmStocks.papers], its maker, and the group it opens. */
private data class StockCard(val index: Int, val maker: String, val group: String?)

private fun films(indices: List<Int>, group: String): List<StockCard> = indices.mapIndexed { i, index ->
    val maker = if (index in setOf(13, 14, 15, 18, 19)) "FUJIFILM" else "KODAK"
    StockCard(index, maker, if (i == 0) group else null)
}

// Positions in FilmStocks.films (the native profile order): Kodak and Fujifilm negatives, the motion picture
// stocks, then the reversal (slide) stocks, which are scanned directly and take no paper.
private val NegativeCards = films(listOf(2, 1, 3, 4, 5, 0, 6, 7, 13, 14, 15), "NEGATIVE")
private val CinemaCards = films(listOf(8, 9, 10, 11, 12), "CINEMA")
private val SlideCards = films(listOf(16, 17, 18, 19), "SLIDE")
private val PaperCards = FilmStocks.papers.indices.map { index ->
    StockCard(index, if (index == 5) "FUJIFILM" else "KODAK", if (index == 0) "PRINT PAPER" else null)
}

@Composable
internal fun FilmStockSheet(
    quick: FilmSimQuickState,
    onSelectPreset: (String) -> Unit,
    /** Field name ("Film" or "Paper") and position. */
    onPick: (String, Int) -> Unit,
    /** Field name and position while a card is held; (null, null) on release. */
    onHold: (String?, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalCaptureHaptics.current
    val film = quick.discretes["Film"]?.selectedIndex ?: -1
    val paper = quick.discretes["Paper"]?.selectedIndex ?: -1
    val gap = CaptureDimens.ControlGap
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(14.dp))
            .padding(vertical = gap)
            .testTag("film_stock_sheet"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SheetLabel("PRESETS")
            Text(
                "TAP: USE · HOLD: PREVIEW",
                color = Color.White.copy(alpha = .55f),
                fontFamily = CaptureMono,
                fontSize = 9.sp,
                style = ViewfinderTextStyle
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(quick.presets.size) { i ->
                val preset = quick.presets[i]
                val on = preset.id == quick.selectedPresetId
                Box(
                    Modifier
                        .height(30.dp)
                        .viewfinderChip(active = on)
                        .captureClickable(shape = CaptureTextRippleShape) {
                            haptics.selection()
                            onSelectPreset(preset.id)
                        }
                        .padding(horizontal = 10.dp)
                        .testTag(CaptureTestTags.filmPreset(preset.id)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        preset.label.uppercase(),
                        color = if (on) CaptureColors.Accent else Color.White.copy(alpha = .85f),
                        fontFamily = CaptureMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        maxLines = 1,
                        style = ViewfinderTextStyle
                    )
                }
            }
        }
        StockRow(NegativeCards, "Film", film, FilmStocks.films, onPick, onHold)
        StockRow(CinemaCards, "Film", film, FilmStocks.films, onPick, onHold)
        StockRow(SlideCards, "Film", film, FilmStocks.films, onPick, onHold)
        // A slide film is scanned directly: no paper to pick.
        if (film !in FilmStocks.positiveFilmIndices) {
            StockRow(PaperCards, "Paper", paper, FilmStocks.papers, onPick, onHold)
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        color = CaptureColors.AccentSoft,
        fontFamily = CaptureMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        style = ViewfinderTextStyle
    )
}

@Composable
private fun StockRow(
    cards: List<StockCard>,
    field: String,
    selected: Int,
    names: List<String>,
    onPick: (String, Int) -> Unit,
    onHold: (String?, Int?) -> Unit
) {
    val group = cards.firstOrNull()?.group
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (group != null) Box(Modifier.padding(horizontal = 10.dp)) { SheetLabel(group) }
        val start = cards.indexOfFirst { it.index == selected }.coerceAtLeast(0)
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(cards.size, key = { cards[it].index }) { i ->
                val card = cards[i]
                StockCardView(card, names.getOrElse(card.index) { "?" }, card.index == selected, field, onPick, onHold)
            }
        }
    }
}

@Composable
private fun StockCardView(
    card: StockCard,
    name: String,
    on: Boolean,
    field: String,
    onPick: (String, Int) -> Unit,
    onHold: (String?, Int?) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    // The latest callbacks: the gesture detector lives on with the card.
    val pick by rememberUpdatedState(onPick)
    val hold by rememberUpdatedState(onHold)
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .width(84.dp)
            .height(48.dp)
            .clip(shape)
            .background(Color(0xFF1C1C1C))
            .then(if (on) Modifier.border(2.dp, CaptureColors.Accent, shape) else Modifier.border(1.dp, CaptureColors.Outline, shape))
            .pointerInput(card.index, field) {
                var held = false
                detectTapGestures(
                    onPress = {
                        held = false
                        tryAwaitRelease()
                        if (held) {
                            held = false
                            hold(null, null)
                        }
                    },
                    onLongPress = {
                        held = true
                        haptics.longPress()
                        hold(field, card.index)
                    },
                    onTap = {
                        haptics.selection()
                        pick(field, card.index)
                    }
                )
            }
            .semantics { contentDescription = "$name${if (on) ", in use" else ""}. Tap to use, hold to preview." }
            .testTag("film_stock_card:$field:${card.index}")
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            card.maker,
            color = if (on) CaptureColors.AccentSoft else Color.White.copy(alpha = .5f),
            fontFamily = CaptureMono,
            fontSize = 8.sp,
            maxLines = 1,
            style = ViewfinderTextStyle
        )
        Text(
            name,
            color = if (on) Color.White else Color.White.copy(alpha = .85f),
            fontFamily = CaptureMono,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ViewfinderTextStyle
        )
    }
}
