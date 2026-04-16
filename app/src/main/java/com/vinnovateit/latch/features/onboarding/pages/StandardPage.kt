package com.vinnovateit.latch.features.onboarding.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.features.onboarding.components.SlideContent
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily

@Composable
fun StandardSlidePage(slide: SlideContent) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = slide.title,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 28.dp)
                .statusBarsPadding()
                .align(Alignment.TopStart)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(180.dp)
                ) {
                    slide.icon()
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = slide.description,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
                    textAlign = TextAlign.Center,
                    fontFamily = SatoshiFontFamily
                )
            }
        }
    }
}


@Composable
fun StandardSlidePageLandscape(slide: SlideContent) {
    PageScaffoldLandscape(slide) { /* No extra content needed */ }
}

@Composable
internal fun PageScaffoldLandscape(slide: SlideContent, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .padding(end = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    slide.icon()
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        slide.title,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        slide.description,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
                        fontFamily = SatoshiFontFamily,
                        textAlign = TextAlign.Start,
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                content()
            }
        }
    }
}