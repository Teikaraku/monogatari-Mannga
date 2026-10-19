package app.monogatari.android.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.monogatari.android.data.Chapter
import app.monogatari.android.ui.tr

@Composable fun ReaderChapterTransition(chapter:Chapter,next:Chapter?,direction:Int,busy:Boolean,advance:()->Unit,back:()->Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal=36.dp,vertical=64.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text(tr(if(direction>0)"FINISHED_COLON" else "CURRENT_COLON"),style=MaterialTheme.typography.titleMedium)
        Text(chapter.title,style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(44.dp))
        Text(tr(if(direction>0)"NEXT_COLON" else "PREVIOUS_COLON"),style=MaterialTheme.typography.titleMedium)
        Text(next?.title ?: tr(if(direction>0)"NO_NEXT_CHAPTER" else "NO_PREVIOUS_CHAPTER"),style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        if(next!=null)Button(advance,enabled=!busy){Text(tr("READ"))}
        if(busy)CircularProgressIndicator(Modifier.padding(16.dp))
        TextButton(back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(8.dp));Text(tr("READER"))}
    }
}
