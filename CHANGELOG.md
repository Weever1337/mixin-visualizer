<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Mixin Visualizer Changelog

## [0.2.0]

### Added

- MixinExtras support: `@ModifyExpressionValue`, `@ModifyReceiver`, `@WrapOperation`, `@WrapMethod`, `@WrapWithCondition` and `@Local` / `@Share` sugar
- `Operation` of `@WrapOperation` and `@WrapMethod` is shown as a lambda that runs the original code
- `@ModifyArgs` with an `Args` object, `@ModifyArg` handlers that take all arguments of the call
- Injection points `INVOKE_ASSIGN`, `CONSTANT`, `JUMP`, `CTOR_HEAD`, `INVOKE_STRING` and `shift = BY`, `slice` and `ordinal` for every injector
- `locals = LocalCapture.CAPTURE_*` in `@Inject`
- "All Mixins" mode that applies every project mixin targeting the class in priority order
- "Compact Diff" mode that shows only the methods a mixin changed
- Mixins targeting nested classes (`Outer.Inner.class`, `targets = "...$1"`)
- Kotlin mixin files
- Classes compiled by Gradle (`build/classes`) are found when the build is built by Gradle
- Preview refreshes after any build, including Ctrl+F9 and Gradle builds

### Changed

- Faster preview: rendered classes are cached and decompilation runs outside of read actions
- Interfaces of a mixin are added to the target, `@Unique` members keep their access
- Handlers with the same name from different mixins are renamed instead of overwriting each other

### Fixed

- `@Redirect` handlers get the receiver and arguments of the redirected call
- `@ModifyConstant` respects `@Constant` values and no longer breaks the method
- `@ModifyVariable` picks the variable the way Mixin does (`index`, `name`, `ordinal`, `argsOnly`) and works at `HEAD`
- `cir.getReturnValue()` and `cancel()` / `setReturnValue()` followed by more code in `@Inject`
- `@ModifyReturnValue` handlers
- Captured target method arguments no longer show up as `null` / `0`
- Initializers of `@Unique` fields in mixins with constructor arguments
- Scrolling to the target method when the selector has a descriptor
- Locals typed as `byte` after `HEAD` injections

### Removed

- Unused plugin dependencies on Kotlin, Maven, Gradle, Groovy and ByteCodeViewer

### Known limitations

- MixinExtras `@Expression` is not supported yet
- `@WrapOperation` on `new` / `instanceof` and `@ModifyExpressionValue` on `new` are not supported yet
- `LocalRef` parameters of `@Local` / `@Share` are passed as `null`
- Only the first target of a multi-target mixin is shown

## [0.1.1]

### Fixed

- Fixed arg remapping
- Removed debug field `__mixin_fields_here__`
- Fixed TAIL handler in `@Inject`

## [0.1.0]

### Added

- Initial release

[Unreleased]: https://github.com/weever1337/mixin-visualizer/compare/0.1.1...HEAD
[0.1.1]: https://github.com/weever1337/mixin-visualizer/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/weever1337/mixin-visualizer/commits/0.1.0
