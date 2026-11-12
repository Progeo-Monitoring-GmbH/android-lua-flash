package com.progeo.core.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.progeo.core.R
import com.progeo.core.legal.Company
import com.progeo.core.ui.theme.ProGeoColors
import com.progeo.core.ui.theme.ProGeoEyebrow

/** The ProGeo logo with the app's name underneath, like the portal's login card. */
@Composable
fun BrandHeader(appName: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.progeo_logo),
            contentDescription = "ProGeo",
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(appName.uppercase(), style = ProGeoEyebrow)
    }
}

/** A panel on the page: the light card with 18 dp corners the portal puts its content in. */
@Composable
fun BrandPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = ProGeoColors.PanelBg,
        contentColor = ProGeoColors.Blue,
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** Company name, address and contact under the panel, as in the portal's mails. */
@Composable
fun CompanyFooter(company: Company = Company.ProGeo, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
        val style = MaterialTheme.typography.bodySmall
        Text(company.name, style = style, fontWeight = FontWeight.Bold, color = ProGeoColors.Blue)
        Text("${company.street} · ${company.postalCity}", style = style, color = ProGeoColors.TextSecondary)
        Text(
            buildAnnotatedString {
                append("Telefon ${company.phone} · ")
                withStyle(SpanStyle(color = ProGeoColors.Blue)) { append(company.email) }
            },
            style = style,
            color = ProGeoColors.TextSecondary,
        )
    }
}

@Composable
fun MessageCard(text: String, error: Boolean = true, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Default.ErrorOutline else Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/**
 * Copies [text] to the clipboard. [sensitive] (configs with passwords, tokens) hides it from
 * the clipboard preview on Android 13+, which also confirms the copy itself; older versions
 * get a toast.
 */
fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean = true) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied to the clipboard", Toast.LENGTH_SHORT).show()
}

/** The text on the clipboard, or null if there is none. */
fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}
