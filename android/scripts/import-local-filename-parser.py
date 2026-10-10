"""Import the complete original ordered parser patterns and all original example cases."""
from pathlib import Path
import re,json
ROOT=Path(__file__).resolve().parents[2]
src=(ROOT/'Monogatari/Core/Sources/BuiltIn/Local/LocalFileNameParser.swift').read_text(encoding='utf-8')
lists={name:re.findall(r'#"(.*?)"#',re.search(r'private static let '+name+r': \[String\] = \[(.*?)\n    \]',src,re.S).group(1)) for name in ['mangaSeriesPatterns','mangaVolumePatterns','mangaChapterPatterns']}
extras=dict(re.findall(r'private static let (\w+) = regex\(#"(.*?)"#\)',src))
raw=lambda value:'"""'+value.replace('$',"${'$'}")+'"""'
out='package app.monogatari.android.local\n\n// Exact ordered regular expressions from LocalFileNameParser.swift; regenerate with scripts/import-local-filename-parser.py.\ninternal object LocalFileNamePatterns {\n'
for name,values in lists.items():out+='    val '+name+'=listOf(\n'+',\n'.join('        '+raw(v) for v in values)+'\n    )\n'
for name,value in extras.items():out+='    val '+name+'='+raw(value)+'\n'
out+='}\n'
(ROOT/'android/app/src/main/java/app/monogatari/android/local/LocalFileNamePatterns.kt').write_text(out,encoding='utf-8')
tests=(ROOT/'MonogatariTests/LocalFileNameParserTests.swift').read_text(encoding='utf-8');cases={}
for func in re.finditer(r'func (testParse\w+)\(filename:',tests):
 block=tests[tests.rfind('@Test(',0,func.start()):func.start()]
 group='volume' if 'Volume' in func.group(1) else 'series' if 'Series' in func.group(1) else 'chapter'
 cases.setdefault(group,[]).extend([[json.loads('"'+a+'"'),json.loads('"'+b+'"')] for a,b in re.findall(r'\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)',block,re.S)])
path=ROOT/'android/app/src/test/resources/local-filename-cases.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(cases,ensure_ascii=False,indent=2),encoding='utf-8')
print({k:len(v) for k,v in lists.items()},{k:len(v) for k,v in cases.items()})
