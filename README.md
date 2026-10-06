# Mixin Visualizer <img src="src/main/resources/META-INF/pluginIcon.svg" width="150" alt="please change this logo in future" align="right">
**Mixin Visualizer** is an IntelliJ IDEA plugin that allows you to preview the effects of SpongePowered Mixins on target classes directly within the IDE.

[![versiones](https://img.shields.io/jetbrains/plugin/v/29218-mixin-visualizer.svg)](https://plugins.jetbrains.com/plugin/29218-mixin-visualizer)
[![downloades](https://img.shields.io/jetbrains/plugin/d/29218-mixin-visualizer.svg)](https://plugins.jetbrains.com/plugin/29218-mixin-visualizer)
> [License](LICENSE) | [CONTRIBUTING](CONTRIBUTING.md) | [Changelog](CHANGELOG.md)
---

## Supported features:
Currently, the plugin implements a custom ASM transformer supporting:
*   **Merging:**
    *   Fields with their initializers, `@Unique` methods, static inits (`<clinit>`)
    *   Interfaces of the mixin are added to the target
    *   `@Shadow` members are skipped
    *   Handlers with the same name from different mixins are renamed instead of overwriting each other
*   **@Overwrite**: Full method replacement
*   **@Redirect**:
    *   Method invocations (`INVOKE`)
    *   Field access (`GETFIELD`/`PUTFIELD`)
*   **@Inject**:
    *   `HEAD`, `TAIL`, `RETURN`, `INVOKE`, `INVOKE_ASSIGN`, `INVOKE_STRING`, `FIELD`, `NEW`, `CONSTANT`, `JUMP`, `CTOR_HEAD`
    *   `shift` (`BEFORE`, `AFTER`, `BY`), `slice` and `ordinal`
    *   `CallbackInfo`: `cancel()`, `setReturnValue()`, `getReturnValue()`
    *   `locals = LocalCapture.CAPTURE_*`
*   **@ModifyArg** / **@ModifyArgs**, **@ModifyConstant**, **@ModifyVariable**
*   **@Accessor** / **@Invoker**
*   **[MixinExtras](https://github.com/LlamaLad7/MixinExtras):** `@ModifyExpressionValue`, `@ModifyReturnValue`, `@ModifyReceiver`, `@WrapOperation`, `@WrapMethod`, `@WrapWithCondition`, `@Local`, `@Share`

Preview itself:
*   Side-by-side diff of the target class before and after the mixin, as decompiled java or ASM bytecode
*   **All Mixins**: applies every mixin of the project that targets the class in priority order
*   **Compact Diff**: shows only the methods the mixin changed
*   Refreshes after every build (Ctrl+F9 or Gradle) and finds classes compiled by Gradle
*   Kotlin mixins and mixins into nested classes

Known limitations:
*   MixinExtras `@Expression` is not supported yet because I don't know how to support it right now =(
*   `@WrapOperation` on `new` / `instanceof` and `@ModifyExpressionValue` on `new` are not supported yet
*   `LocalRef` parameters of `@Local` / `@Share` are passed as `null`
*   Only the first target of a multi-target mixin is shown

More to see: [Mixin Documentation](https://github.com/SpongePowered/Mixin/wiki)

## TODOs (todo: use Projects in GH):
The project is a Work In Progress, with the following planned features:
- [x] **Core transformation logic** (ASM-based)
- [x] **UI features** (Editor tabs, Diff view, Toolbar)
- [x] **Bytecode/Decompiler toggle**
- [x] **Handle Shadow members** (skip/validate them properly)
- [x] **Handle super calls**
- [x] **More @At targets** (`FIELD`, `NEW`, `INVOKE_ASSIGN`, `CONSTANT`, `JUMP`, `CTOR_HEAD`, etc.)
- [x] **Static inits** (`<clinit>` merging)
- [x] **Exception handling** (try-catch blocks in injections)
- [ ] **Support 3rd party extensions:**
    - [x] [MixinExtras](https://github.com/LlamaLad7/MixinExtras) (WrapOperation, etc.)
    - [ ] MixinExtras expressions (`@Expression`)
    - [ ] [MixinSquared](https://github.com/Bawnorton/MixinSquared) (button to see class transformed with MixinSquared)
- [x] **Improve code structure and error handling** (refactor transformer code, please)
- [x] **Auto compile and refresh on file save** (maybe settings to control this behavior)
- [x] **Syntax highlighting for bytecode/decompiler view**
- [x] **Handle @Accessor and @Invoker**
- [x] **Fix variable names**
- [x] **Apply all project mixins at once** (All Mixins mode)
- [x] **Report injectors that didn't apply**
- [x] **Tests for the transformer**
- [ ] **Target selector for multi-target mixins**
- [ ] **Add mixin builder**

## Tested on:
* IntelliJ IDEA 2025.1.4.1+ (Community & Ultimate)
* Java 8+
* Forge 1.16.5 - 1.20.1
* NeoForge 1.21.1 - 26.2
* Fabric 1.20.1 - 26.3
* Mixin 0.8.7+
* Mixin 0.8.7 + Mixin Extras

## Usage:
1. Install the plugin from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29218-mixin-visualizer) (it needs the [Minecraft Development](https://plugins.jetbrains.com/plugin/8327-minecraft-development) plugin).
2. Open a project with Mixin usage and build it once (Ctrl+F9 or Gradle), the preview works on compiled classes.
3. Open a mixin class and switch to the "Mixin Preview" tab at the bottom of the editor (or use the shortcut `ALT-SHIFT-RIGHT`) or click icon in the gutter next to the class or an injector method
4. Explore the target class in the Diff View. The toolbar toggles the bytecode view, *All Mixins* and Compact Diff.

---
*Note: This plugin is not affiliated with [SpongePowered](https://github.com/SpongePowered) and [MixinExtras](https://github.com/LlamaLad7/MixinExtras).*
