package moe.chenxy.oppopods.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

@Composable
fun AboutPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            context.startActivity(this)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // HybridPods 项目信息
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HybridPods",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.about_hybridpods_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                BasicComponent(
                    title = "GitHub",
                    summary = "Nanawwa/HybridPods",
                    onClick = { openUrl("https://github.com/Nanawwa/HybridPods") }
                )
            }
        }

        // 项目来源
        item {
            Card {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Text(
                        text = stringResource(R.string.about_lineage_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicComponent(
                    title = "OppoPods",
                    summary = stringResource(R.string.about_oppopods_desc),
                    onClick = { openUrl("https://github.com/1812z/OppoPods") }
                )
                BasicComponent(
                    title = "OppoPods (Original)",
                    summary = stringResource(R.string.about_oppopods_orig_desc),
                    onClick = { openUrl("https://github.com/Leaf-lsgtky/OppoPods") }
                )
                BasicComponent(
                    title = "HyperPods",
                    summary = stringResource(R.string.about_hyperpods_desc),
                    onClick = { openUrl("https://github.com/Art-Chen/HyperPods") }
                )
            }
        }

        // 鸣谢
        item {
            Card {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Text(
                        text = stringResource(R.string.about_credits_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicComponent(
                    title = "Miuix",
                    summary = stringResource(R.string.about_miuix_desc),
                    onClick = { openUrl("https://github.com/YuKongA/miuix") }
                )
                BasicComponent(
                    title = stringResource(R.string.about_mishuai_protocol),
                    summary = stringResource(R.string.about_mishuai_protocol_desc),
                )
            }
        }
    }
}
