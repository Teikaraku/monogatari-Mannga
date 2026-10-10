import fs from 'node:fs';
const root = new URL('../app/src/main/java/app/monogatari/android/ui/', import.meta.url);
const path = new URL('TrackingScreens.kt', root);
let text = fs.readFileSync(path, 'utf8');
const keys = {
  '服务器地址':'SERVER_URL','登录服务器':'LOGIN','添加追踪':'START_TRACKING','导入进度':'SYNC_LOCAL_HISTORY','管理追踪账号':'TRACKERS',
  '解绑追踪':'STOP_TRACKING','解绑':'STOP_TRACKING','取消':'CANCEL','请先登录追踪账号。':'NOT_LOGGED_IN','登录账号':'LOGIN','漫画标题':'SERIES_TITLE',
  '搜索':'SEARCH','已读章节':'CHAPTERS','已读卷数':'VOLUMES','同步保存':'SAVE','完成授权':'DONE'
};
for (const [value,key] of Object.entries(keys)) text = text.replaceAll(`Text("${value}")`, `Text(tr("${key}"))`);
const fallback = {
  '授权回调':['OAuth callback','授权回调'], '粘贴浏览器完整回调地址':['Paste the full browser callback URL','粘贴浏览器完整回调地址'],
  'Bearer 令牌（可选）':['Bearer token (optional)','Bearer 令牌（可选）'], '登录信息与漫画源共用，可在漫画源设置中修改。':['Credentials are shared with this source. Edit them in source settings.','登录信息与漫画源共用，可在漫画源设置中修改。'],
  '浏览器 OAuth 登录':['Browser OAuth login','浏览器 OAuth 登录'], '个人访问令牌':['Personal access token','个人访问令牌'],
  '使用令牌登录':['Log in with token','使用令牌登录'], 'OAuth 应用设置':['OAuth application settings','OAuth 应用设置'],
  'Client Secret（保留现有值请留空）':['Client secret (leave blank to keep existing)','Client Secret（保留现有值请留空）'],
  '已注册的回调 URL':['Registered callback URL','已注册的回调 URL'], '从此漫画移除绑定？远端收藏会保留。':['Remove tracking from this manga? Your remote collection is retained.','从此漫画移除绑定？远端收藏会保留。'],
  '已收藏':['Already tracked','已收藏'], '开始日期 yyyy-MM-dd（留空清除）':['Started yyyy-MM-dd (blank to clear)','开始日期 yyyy-MM-dd（留空清除）'],
  '结束日期 yyyy-MM-dd（留空清除）':['Finished yyyy-MM-dd (blank to clear)','结束日期 yyyy-MM-dd（留空清除）'],
  '章节偏移（本地章节号 + 偏移）':['Chapter offset (source chapter + offset)','章节偏移（本地章节号 + 偏移）']
};
for (const [value,translations] of Object.entries(fallback)) text = text.replaceAll(`Text("${value}"`, `Text(pt(${translations.map(JSON.stringify).join(', ')})`);
text = text.replaceAll('Text("待同步 ${tracking.bindings.count { it.pending }} 条"', 'Text(pt("Pending sync", "待同步") + " (${tracking.bindings.count { it.pending }})"');
text = text.replaceAll('Text("已登录：${account.username}"', 'Text(tr("LOGIN") + ": ${account.username}"');
text = text.replaceAll('Text("${b.error}；已保存到待同步队列"', 'Text(b.error + " · " + pt("Saved for retry", "已保存到待同步队列")');
text = text.replaceAll('"尚未绑定追踪", "连接账号后，搜索并选择对应条目。", "添加追踪"', 'tr("START_TRACKING"), pt("Log in, then search and select a matching item.", "连接账号后，搜索并选择对应条目。"), tr("START_TRACKING")');
text = text.replaceAll('Text("使用自己的 OAuth 应用时，注册 aidoku-android://oauth/${account.provider.id} 为回调 URL。"', 'Text(pt("Register this callback URL for your own OAuth application:", "使用自己的 OAuth 应用时注册此回调 URL：") + " aidoku-android://oauth/${account.provider.id}"');
for (const value of ['服务器已连接','账号已登录','已导入远端阅读进度']) text = text.replaceAll(`vm.message = "${value}"`, 'vm.message = Localization.string(vm.repo.context, "DONE")');
text = text.replaceAll('e.message ?: "操作失败"', 'e.message ?: "Operation failed"');
fs.writeFileSync(path,text);
const backup = new URL('CompleteBackupsScreen.kt',root);
let b = fs.readFileSync(backup,'utf8').replaceAll('ToggleRow("合并已有数据 / Merge"','ToggleRow(pt("Merge existing data", "合并已有数据")')
  .replaceAll('if (merge) "将备份与当前数据合并；同一条目的备份值优先。"','if (merge) pt("Merge the backup with current data. Backup values take precedence for matching entries.", "将备份与当前数据合并；同一条目的备份值优先。")')
  .replaceAll('"\\n仅替换所选数据项。"','"\\n" + pt("Only selected data is replaced.", "仅替换所选数据项。")');
fs.writeFileSync(backup,b);
const dictionary = new URL('DictionaryScreens.kt', root); let d = fs.readFileSync(dictionary,'utf8').replaceAll('error("无法创建导出文件")', 'error("Cannot create export file")'); fs.writeFileSync(dictionary,d);
