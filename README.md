# Mixin Visualizer [UNSTABLE] <img src="src/main/resources/META-INF/pluginIcon.svg" width="150" alt="please change this logo in future" align="right">
**Mixin Visualizer** is an IntelliJ IDEA plugin that allows you to preview the effects of SpongePowered Mixins on target classes directly within the IDE.

> [License](LICENSE) | [CONTRIBUTING](CONTRIBUTING.md) | [Changelog](CHANGELOG.md)
---

## Supported features:
Currently, the plugin implements a custom ASM transformer supporting:
*   **Merging:**
    *   Fields (shadows handled partially)
    *   Unique methods
*   **@Overwrite**: Full method replacement
*   **@Redirect**:
    *   Method invocations (`INVOKE`)
    *   Field access (`GETFIELD`/`PUTFIELD`)
*   **@Inject**:
    *   `HEAD`: Injection at the start.
    *   `TAIL` / `RETURN`: Injection before return statements.
    *   `INVOKE`: Injection before/after specific method calls (supports `shift`).
    *   *Basic local variable remapping is implemented.*

More to see: [Mixin Documentation](https://github.com/SpongePowered/Mixin/wiki)

## TODOs:
The project is a Work In Progress, with the following planned features:
- [x] **Core transformation logic** (ASM-based)
- [x] **UI features** (Editor tabs, Diff view, Toolbar)
- [x] **Bytecode/Decompiler toggle**
- [ ] **Handle Shadow members** (skip/validate them properly)
- [ ] **Handle Priority & conflicts** (support `@Priority`)
- [ ] **Handle super calls** (calls to mixin superclass methods)
- [ ] **More @At targets** (support `FIELD`, `NEW`, etc.)
- [ ] **Static inits** (`<clinit>` merging)
- [ ] **Exception handling** (try-catch blocks in injections)
- [ ] **Support 3rd party extensions:**
    - [ ] [MixinExtras](https://github.com/LlamaLad7/MixinExtras) (WrapOperation, etc.)
    - [ ] [MixinSquared](https://github.com/Bawnorton/MixinSquared) (button to see class transformed with MixinSquared)
- [x] Improve code structure and error handling (refactor transformer code, please)
- [x] Auto compile and refresh on file save (maybe settings to control this behavior)
- [x] Syntax highlighting for bytecode/decompiler view

## Tested on:
* IntelliJ IDEA 2025.1.4.1+ (Community & Ultimate)
* Java 17+ && Java 21+
* Forge 1.20.1
* NeoForge 1.21.1
* Fabric 1.21.7
* Mixin 0.8.7+

## Usage:
1. Install the plugin from the... Oh no, it's not published yet =(
2. Open a project with Mixin usage.
3. Open a class that is a Mixin target.
4. Click the "Mixin Preview" button in the toolbar or use the shortcut `ALT-SHIFT-RIGHT` to open the Mixin Visualizer tab.
5. Explore the transformed class in the new tab, using the Diff View to see changes.

---
*Note: This plugin is not affiliated with [SpongePowered](https://github.com/SpongePowered).*