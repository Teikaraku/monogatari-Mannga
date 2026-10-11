package app.monogatari.android.local

// Exact ordered regular expressions from LocalFileNameParser.swift.
internal object LocalFileNamePatterns {
    val mangaSeriesPatterns=listOf(
        """(?<Series>.+?)(เล่ม|เล่มที่)(\s)?(\.?)(\s|_)?(?<Volume>\d+(\-\d+)?(\.\d+)?)""",
        """(?<Series>.+?)Том(а?)(\.?)(\s|_)?(?<Volume>\d+(?:(\-)\d+)?)""",
        """(?<Series>.+?)(\s|_)?(?<Volume>\d+(?:(\-)\d+)?)(\s|_)Том(а?)""",
        """(?<Series>.+?)(?!Том)(?<!Том\.)\s\d+(\s|_)?(?<Chapter>\d+(?:\.\d+|-\d+)?)(\s|_)(Глава|глава|Главы|Глава)""",
        """(?<Series>.+?)(Глава|глава|Главы|Глава)(\.?)(\s|_)?(?<Chapter>\d+(?:.\d+|-\d+)?)""",
        """(?<Series>.*)(\b|_|-|\s)(?:sp)\d""",
        """(?<Series>.+?)(\s|_|-)+(?:Vol(ume|\.)?(\s|_|-)+\d+)(\s|_|-)+(?:(Ch|Chapter|Ch)\.?)(\s|_|-)+(?<Chapter>\d+)""",
        """^(?<Series>.+?)(?:\s*|_|\-\s*)+(?:Ch(?:apter|\.|)\s*\d+(?:\.\d+)?(?:\s*|_|\-\s*)+)?Vol(?:ume|\.|)\s*(?:\d+|tbd)(?:\s|_|\-\s*).+""",
        """(?<Series>.*)(\b|_)v(?<Volume>\d+-?\d*)(\s|_|-)""",
        """(?<Series>.+?)( - )(?:v|vo|c|chapters|tome|t|ch)\d""",
        """(?<Series>.*)(?:, Chapter )(?<Chapter>\d+)""",
        """(?<Series>.+?)(\s|_|-)(?!Vol)(\s|_|-)((?:Chapter)|(?:Ch\.))(\s|_|-)(?<Chapter>\d+)""",
        """(?<Series>.+?):? (\b|_|-)(vol|tome)\.?(\s|-|_)?\d+""",
        """(?<Series>.+?):?(\s|\b|_|-)Chapter(\s|\b|_|-)\d+(\s|\b|_|-)(vol)(ume)""",
        """(?<Series>.+?):? (\b|_|-)(t\d+|tome(\b|_)\d+)""",
        """(?<Series>.+?):? (\b|_|-)(vol)(ume)""",
        """(?<Series>.*?)(?<!\()\bc\d+\b""",
        """(?<Series>.*)(?: _|-|\[|\()\s?(vol(ume)?|tome|t\d+)""",
        """^(?<Series>(?!Vol).+?)(?:(ch(apter|\.)(\b|_|-|\s))|sp)\d""",
        """(?<Series>.*) (\b|_|-)(v|ch\.?|c|s)\d+""",
        """(?<Series>.*)\s+(?<Chapter>\d+)\s+(?:\(\d{4}\))\s""",
        """(?<Series>.*) (-)?(?<Chapter>\d+(?:.\d+|-\d+)?) \(\d{4}\)""",
        """(?<Series>.*)(\s|_)(?:Episode|Ep\.?)(\s|_)(?<Chapter>\d+(?:.\d+|-\d+)?)""",
        """(?<Series>.*)\(\d""",
        """(?<Series>.*)(\s|_)\((c\s|ch\s|chapter\s)""",
        """(?<Series>.+?)(\s|_|\-)+?chapters(\s|_|\-)+?\d+(\s|_|\-)+?""",
        """(?<Series>.+?)(\s|_|\-)+?\d+(\s|_|\-)\(""",
        """(?<Series>.*)(v|s)\d+(-\d+)?(_|\s)""",
        """(?<Series>.*)(v|s)\d+(-\d+)?""",
        """(?<Series>.*)(_)(v|vo|c|volume)( |_)\d+""",
        """(?<Series>.*)( |_)(vol\d+)?( |_)(?:Chp\.? ?\d+)""",
        """(?<Series>.*)( |_)(?:Chp.? ?\d+)""",
        """^(?!Vol)(?<Series>.*)( |_)Chapter( |_)(\d+)""",
        """^(?!vol)(?<Series>.*)( |_)(chapters( |_)?)\d+-?\d*""",
        """^(?!Vol\.?)(?<Series>.*)( |_|-)(?<!-)(episode|chapter|(ch\.?) ?)\d+-?\d*""",
        """^(?!Vol)(?<Series>.*)ch\d+-?\d?""",
        """(?<Series>.*)( ?- ?)Ch\.\d+-?\d*""",
        """^(?!Vol)(?!Chapter)(?<Series>.+?)(-|_|\s|#)\d+(-\d+)?(권|화|話)""",
        """^(?!Vol)(?!Chapter)(?<Series>.+?)(-|_|\s|#)\d+(-\d+)?""",
        """^(?!Vol\.?)(?!Chapter)(?<Series>.+?)(\s|_|-)(?<!-)(ch|chapter)?\.?\d+-?\d*""",
        """^(?!Vol)(?<Series>.*)( |_|-)(ch?)\d+""",
        """(?<Series>.+?)第(?<Volume>\d+(?:(\-)\d+)?)巻"""
    )
    val mangaVolumePatterns=listOf(
        """(เล่ม|เล่มที่)(\s)?(\.?)(\s|_)?(?<Volume>\d+(\-\d+)?(\.\d+)?)""",
        """(?<Series>.*)(\b|_)(v|tome(\s|_)?|t)(?<Volume>\d+-?\d+)(\s|_)""",
        """^(?<Series>.+?)(\s*Chapter\s*\d+)?(\s|_|\-\s)+((Vol(ume)?|tome)\.?(\s|_)?)(?<Volume>\d+(\.\d+)?)(.+?|${'$'})""",
        """(?<Series>.*)(\b|_)(?!\[)v(?<Volume>\d+(\.\d)?(-\d+(\.\d)?)?)(?!\])(\b|_)""",
        """(?<Series>.*)(\b|_)(vol\.? ?)(?<Volume>\d+(\.\d)?(-\d+)?(\.\d)?)""",
        """(vol\.? ?)(?<Volume>\d+(\.\d)?)""",
        """((volume|tome)\s)(?<Volume>\d+(\.\d)?)""",
        """(?<Series>.*)(\b|_)((S|T)(?<Volume>\d+)(\b|_))""",
        """(vol_)(?<Volume>\d+(\.\d)?)""",
        """第(?<Volume>\d+)(卷|册)""",
        """(卷|册)(?<Volume>\d+)""",
        """제?(?<Volume>\d+(\.\d+)?)(권|화|장)""",
        """시즌(?<Volume>\d+(\-\d+)?)""",
        """(?<Volume>\d+(\-|~)?\d+?)시즌""",
        """시즌(?<Volume>\d+(\-|~)?\d+?)""",
        """(?<Volume>\d+(?:(\-)\d+)?)巻""",
        """Том(а?)(\.?)(\s|_)?(?<Volume>\d+(?:(\-)\d+)?)""",
        """(\s|_)?(?<Volume>\d+(?:(\-)\d+)?)(\s|_)Том(а?)"""
    )
    val mangaChapterPatterns=listOf(
        """(?<Volume>((เล่ม|เล่มที่))?(\s|_)?\.?\d+)(\s|_)(บทที่|ตอนที่)\.?(\s|_)?(?<Chapter>\d+)""",
        """(\b|_)(c|ch)(\.?\s?)(?<Chapter>(\d+(\.\d)?)(-c?\d+(\.\d)?)?)""",
        """v\d+\.(\s|_)(?<Chapter>\d+(?:.\d+|-\d+)?)""",
        """^(?<Series>.*)(?: |_)#(?<Chapter>\d+)""",
        """^(?!Vol)(?<Series>.*)\s?(?<!vol\. )\sChapter\s(?<Chapter>\d+(?:\.?[\d-]+)?)""",
        """(Глава|глава|Главы|Глава)(\.?)(\s|_)?(?<Chapter>\d+(?:.\d+|-\d+)?)""",
        """^(?<Series>.+?)(?<!Vol)(?<!Vol.)(?<!Volume)\s(\d\s)?(?<Chapter>\d+(?:\.\d+|-\d+)?)(?:\s\(\d{4}\))?(\b|_|-)""",
        """(?<Series>.*)\sS(?<Volume>\d+)\s(?<Chapter>\d+(?:.\d+|-\d+)?)""",
        """^((?!v|vo|vol|Volume).)*(\s|_)(?<Chapter>\.?\d+(?:.\d+|-\d+)?)(?<Part>b)?(\s|_|\[|\()""",
        """Chapter(?<Chapter>\d+(-\d+)?)""",
        """(?<Series>.*)(\s|_)(vol\d+)?(\s|_)Chp\.? ?(?<Chapter>\d+)""",
        """(?<Volume>((vol|volume|v))?(\s|_)?\.?\d+)(\s|_)(Chp|Chapter)\.?(\s|_)?(?<Chapter>\d+)""",
        """第(?<Chapter>\d+)话""",
        """제?(?<Chapter>\d+\.?\d+)(회|화|장)""",
        """第?(?<Chapter>\d+(?:\.\d+|-\d+)?)話""",
        """(?!Том)(?<!Том\.)\s\d+(\s|_)?(?<Chapter>\d+(?:\.\d+|-\d+)?)(\s|_)(Глава|глава|Главы|Глава)"""
    )
    val duplicateVolumeRegex="""(?i)(vol\.?|volume|v)(\s|_)*\d+.*?(vol\.?|volume|v)(\s|_)*\d+"""
    val duplicateChapterRegex="""(?i)(ch\.?|chapter|c)(\s|_)*\d+.*?(ch\.?|chapter|c)(\s|_)*\d+"""
    val volumeRangeRegex="""(vol\.?|v)(\s|_)?\d+(\.\d+)?-(vol\.?|v)(\s|_)?\d+(\.\d+)?"""
    val chapterRangeRegex="""(ch\.?|c)(\s|_)?\d+(\.\d+)?-(ch\.?|c)(\s|_)?\d+(\.\d+)?"""
    val volumeNumberRegex="""(vol\.?|volume|v)(\s|_)*(?<Volume>\d+(\.\d+)?(-\d+(\.\d+)?)?)"""
    val chapterNumberRegex="""(ch\.?|chapter|c)(\s|_)*(?<Chapter>\d+(\.\d+)?(-\d+(\.\d+)?)?)"""
    val mangaEditionRegex="""\b(?:Omnibus(?:\s?Edition)?|Uncensored)\b"""
    val cleanupRegex="""(?:\([^()]*\)|\[[^\[\]]*\]|\{[^\{\}]*\}|\{Complete\})"""
    val emptySpaceRegex="""\s{2,}"""
}
