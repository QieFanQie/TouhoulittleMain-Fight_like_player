<#
  一键跑全部离线自测 + 清单校验。

  为什么要这个脚本：M2SelfTest 需要 Gson（读 catalog/*.json），而 Gson 不在
  我们自己的运行时 classpath 上 —— 它由 Forge/Minecraft 提供。为了能【不启动游戏】
  跑自测，这里从 gradle 缓存里找到 gson jar 并显式加进 -cp。

  用法（在仓库根）：
    powershell -ExecutionPolicy Bypass -File tools\run_selftests.ps1
  退出码 0 = 全过。
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$java = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\bin\java.exe'
$py   = 'C:\Users\win\anaconda3\python.exe'

if (-not (Test-Path $java)) { Write-Error "找不到 JDK：$java"; exit 2 }
$env:PYTHONIOENCODING = 'utf-8'

# ── 找 gson（Forge/Minecraft 自带，不在我们的 build 产物里）──
$gson = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" `
            -Recurse -Filter 'gson-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object Name -Descending | Select-Object -First 1
if (-not $gson) {
    Write-Error '找不到 gson jar（M2SelfTest 需要它读 catalog/*.json）'
    exit 2
}
Write-Host "gson = $($gson.FullName)" -ForegroundColor DarkGray

$cp = "build\classes\java\main;$($gson.FullName)"
$fail = 0
$steps = 0

# ── ★★ 第 0 步：先确认"跑的是刚改的代码" ──
#  为什么必须做（2026-10-01 实际踩到）：本脚本只【运行】build\classes 里的 class，
#  自己不编译。于是"改了源码 → 直接跑自测"会跑在【旧 class】上，
#  结果是"全绿"而新测试根本没执行 —— 一种最坏的假绿。
#  ⇒ 判据：src/main/java 下有没有比 build\classes\java\main 更新的 .java。
#     有 ⇒ 自动编译一次；编译失败 ⇒ 直接退出（不允许拿旧 class 出结论）。
$srcRoot = 'src\main\java'
$clsRoot = 'build\classes\java\main'
$newestSrc = Get-ChildItem $srcRoot -Recurse -Filter '*.java' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$oldestCls = Get-ChildItem $clsRoot -Recurse -Filter '*.class' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime | Select-Object -First 1
if (-not $oldestCls -or ($newestSrc -and $newestSrc.LastWriteTime -gt $oldestCls.LastWriteTime)) {
    Write-Host "源码比 class 新 ⇒ 先编译（否则自测跑的是旧代码，会假绿）" -ForegroundColor Yellow
    $gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter 'gradle.bat' `
                -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'gradle-8\.14\.3' } | Select-Object -First 1
    if (-not $gradle) {
        Write-Error "源码比 class 新，但找不到 gradle 8.14.3（见 README 的构建说明）⇒ 拒绝在旧 class 上跑自测"
        exit 3
    }
    # ★★ 第 15 轮修：这一行**必须走 cmd /c 并重定向到日志**。
    #   原因（当场踩到）：本脚本 $ErrorActionPreference = 'Stop'，而 PowerShell **5.1**
    #   会把【本机命令写到 stderr 的任何一行】当成终止性错误 —— javac 的
    #   「注: 某些输入文件使用或覆盖了已过时的 API。」正好走 stderr
    #   ⇒ 编译其实**成功**，脚本却在第 58 行直接死掉（表现为"套件没跑完、也没有失败信息"）。
    #   ⇒ 判据：**要把"成功但话多"和"真的失败"分开**：重定向进日志，然后只看 $LASTEXITCODE。
    $compileLog = '_scratch\run_compile.log'
    cmd /c "`"$($gradle.FullName)`" classes --console=plain -q > `"$compileLog`" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  编译输出（尾部）：" -ForegroundColor DarkGray
        Get-Content $compileLog -Tail 25 -Encoding UTF8 | ForEach-Object { Write-Host "    $_" }
        Write-Error "编译失败 ⇒ 拒绝在旧 class 上跑自测（先修编译错误）"
        exit 3
    }
    Write-Host "编译完成" -ForegroundColor Green
}

function Run-Step($name, $exe, $argline, $log) {
    Write-Host ""
    Write-Host "=== $name ===" -ForegroundColor Cyan
    $script:steps++
    cmd /c "`"$exe`" $argline > `"$log`" 2>&1"
    $code = $LASTEXITCODE
    Get-Content $log -Encoding UTF8 | Select-Object -Last 4
    if ($code -ne 0) {
        Write-Host "  >>> 失败 (exit $code)，完整日志：$log" -ForegroundColor Red
        $script:fail++
    }
    return $code
}

New-Item -ItemType Directory -Force -Path _scratch | Out-Null

# 1) 弹簧核心（M1）—— 不需要 gson
Run-Step 'M1 弹簧核心自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"build\classes\java\main`" com.touhoulittlemad.fightlikeplayer.decision.SpringSelfTest" `
    '_scratch\run_m1.log' | Out-Null

# 1b) ★ 步法（走位）—— 不需要 gson
Run-Step '步法自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"build\classes\java\main`" com.touhoulittlemad.fightlikeplayer.decision.GaitSelfTest" `
    '_scratch\run_gait.log' | Out-Null

# 1c) ★ 弹簧动力学（受伤冲量 / 动态忘却）—— 不需要 gson
Run-Step '弹簧动力学自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"build\classes\java\main`" com.touhoulittlemad.fightlikeplayer.carrier.SpringDynamicsSelfTest" `
    '_scratch\run_dynamics.log' | Out-Null

# 1d) ★★ 思维层（JEV 客户端 / 局势翻译 / 答案→8 轴向量 / 调度器）—— 需要 gson
#     ★ 这一项【不联网】：用假 Transport 验调度器，用固定样例验解析与向量映射。
#       要打真接口另跑：ThinkSelfTest --live（需要环境变量 JEV_API_KEY）
Run-Step '思维层自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"$cp`" com.touhoulittlemad.fightlikeplayer.decision.thinking.ThinkSelfTest" `
    '_scratch\run_think.log' | Out-Null

# 2) 载体解析（M2）—— 需要 gson
Run-Step 'M2 载体解析自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"$cp`" com.touhoulittlemad.fightlikeplayer.carrier.M2SelfTest" `
    '_scratch\run_m2.log' | Out-Null

# 2c) 清单加载 / 热重载（M3）—— 需要 gson
Run-Step 'M3 清单加载/热重载自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"$cp`" com.touhoulittlemad.fightlikeplayer.carrier.M3SelfTest" `
    '_scratch\run_m3.log' | Out-Null

# 2d) 端到端链路（偏置 -> 载体解析 -> 决策）—— 需要 gson
Run-Step '端到端链路自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"$cp`" com.touhoulittlemad.fightlikeplayer.carrier.PipelineSelfTest" `
    '_scratch\run_pipeline.log' | Out-Null

# 2e) ★ 游戏侧循环回放（真实清单 + 门控/冷却/默认动作/走位不在候选）—— 需要 gson
Run-Step '游戏侧循环回放自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"$cp`" com.touhoulittlemad.fightlikeplayer.carrier.GameLoopSelfTest" `
    '_scratch\run_gameloop.log' | Out-Null

# 3) 弹簧空间 / 双循环 / 有向轴 —— 不需要 gson
Run-Step '弹簧空间自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"build\classes\java\main`" com.touhoulittlemad.fightlikeplayer.decision.SpringSpaceSelfTest" `
    '_scratch\run_space.log' | Out-Null

# 4) 清单校验
Run-Step '清单校验' $py 'tools\validate_catalog.py' '_scratch\run_catalog.log' | Out-Null

# 5) v_x 覆盖率
Run-Step 'v_x 覆盖率' $py 'tools\vector_coverage.py' '_scratch\run_veccov.log' | Out-Null

# 6) 轴名对账（防止清单轴名与 Java 枚举不一致而【静默失效】)
Run-Step '轴名对账' $py 'tools\check_axis_names.py' '_scratch\run_axes.log' | Out-Null

# 7) 文档链接完整性
Run-Step '文档链接完整性' $py 'tools\check_doc_links.py' '_scratch\run_links.log' | Out-Null

# 8) 隔离性不变量（入口层不得引用 TLM；纯逻辑层不得引用 MC）
Run-Step '隔离性不变量' $py 'tools\check_isolation.py' '_scratch\run_iso.log' | Out-Null

# 9) 执行器覆盖审计（M4：哪些动作还没有执行器）
Run-Step '执行器覆盖审计' $py 'tools\check_executors.py' '_scratch\run_exec.log' | Out-Null

# 10) ★★ 承诺时长审计（2026-09-30 事故：45/56 个动作的承诺=0 ⇒ 门控失效 + 弓永不松手）
Run-Step '承诺时长审计' $py 'tools\audit_durations.py' '_scratch\run_durations.log' | Out-Null

# 11) ★★ 前置条件可达性审计（同一事故的另一半：13/54 个动作在游戏里永远拿不到）
Run-Step '前置条件可达性审计' $py 'tools\audit_reachability.py' '_scratch\run_reach.log' | Out-Null

# 12) ★★ 能力键对账（2026-10-01 实测「铁魔法法术全部无法释放」的根因：
#     carriers.json 声明了 capability 键 irons:isSpellContainer，而游戏侧从没喂过
#     ⇒ 载体规则永不命中 ⇒ 铁魔法一个候选都进不了，且完全静默）
Run-Step '能力键对账' $py 'tools\audit_capabilities.py' '_scratch\run_caps.log' | Out-Null

# 13) ★★ 法术意图表完整性（W32）：jar 里每个法术类都必须在表里有一明确分类，
#     否则新版本加了法术会【静默落到关键词兜底】⇒ 没人知道它其实没被复核过
Run-Step '法术意图表审计' $py 'tools\audit_spell_intent.py' '_scratch\run_spellintent.log' | Out-Null

# 14) ★★ 引号陷阱扫描（本项目已犯过 8 次的那种）：
#     Java 字符串 / JSON 字符串里【该用「」却写了 ASCII 双引号】——
#     在 Java 里是编译错误，在 JSON 里是"整份文件解析失败"（2026-10-01 刚踩过一次：
#     一处改动让 9 个自测步骤同时报错，而报错只说 Unterminated object）。
Run-Step '引号陷阱扫描' $py 'tools\check_quote_trap.py' '_scratch\run_quotes.log' | Out-Null
# 15) ★★ 指令系统（docs/15 的 M1/M2 判据）：白名单/TTL/互斥/候选集过滤/步法约束
#     ★ 第 14 轮起本步还要验【战斗场景真的进了给模型的那段 JSON】⇒ 需要 gson（用 $cp）
#       —— 少了它这里会 NoClassDefFoundError: com/google/gson/Gson（当场报错，不会假绿）
Run-Step '指令系统自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"$cp`" com.touhoulittlemad.fightlikeplayer.decision.DirectiveSelfTest" `
    '_scratch\run_directive.log' | Out-Null
# 16) ★★ 拔刀剑时间线（第十六轮取证得来）：松手时刻 / 松手成功判据 / 【连段每 tick 都要推】
#     ★ 最后那条就是「SA 触发了却零效果」的根因所在，必须被钉住
#       （本轮已因"没人推那一帧"栽过一次，见 docs/15 §3.9）。
Run-Step '拔刀剑时间线自测' $java `
    "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp `"build\classes\java\main`" com.touhoulittlemad.fightlikeplayer.decision.SlashArtSelfTest" `
    '_scratch\run_slashart.log' | Out-Null

# 17) ★★ 瞬间指令接线审计（委托方 2026-10-05 实测「瞬时指令无法生效」的根因）：
#     指令的每 tick 推进原来挂在"只在有攻击目标时运行"的行为上
#     ⇒ 非战斗时瞬间指令全部作废、换手永不发生。这一步钉住"必须挂在 MaidTickEvent 上"。
Run-Step '瞬间指令接线审计' $py 'tools\check_instant_wiring.py' '_scratch\run_instant.log' | Out-Null


Write-Host ""
if ($fail -eq 0) {
    # ★ 步数【动态计数】—— 原来硬编码 "14/14"，加了第 15/16 步之后它就开始骗人
    Write-Host "全部通过 ($steps/$steps)" -ForegroundColor Green
    exit 0
} else {
    Write-Host "$fail 项失败（共 $steps 步）" -ForegroundColor Red
    exit 1
}
