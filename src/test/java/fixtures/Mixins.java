package fixtures;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

public class Mixins {
    @Mixin(Target.class)
    public static class ReturnPeek {
        @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
        private void capAt10(int input, CallbackInfoReturnable<Integer> cir) {
            if (cir.getReturnValue() > 10) cir.setReturnValue(10);
        }
    }

    @Mixin(Target.class)
    public static class CancelThenWork {
        @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
        private void stopLater(CallbackInfo ci) {
            if (System.nanoTime() > 0L) ci.cancel();
            System.out.println("after cancel");
        }
    }

    @Mixin(Target.class)
    public static class CancelTail {
        @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
        private void stop(CallbackInfo ci) {
            if (System.nanoTime() > 0L) {
                System.out.println("stop");
                ci.cancel();
            }
        }
    }

    @Mixin(Target.class)
    public static class SetReturnThenWork {
        @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
        private void early(int input, CallbackInfoReturnable<Integer> cir) {
            if (input < 0) cir.setReturnValue(0);
            System.out.println("still here");
        }
    }

    @Mixin(Target.class)
    public static class AfterAssign {
        @Inject(method = "speed", at = @At(value = "INVOKE_ASSIGN", target = "Lfixtures/Target$Helper;scale(I)I"))
        private void afterScale(float base, int mult, CallbackInfoReturnable<Float> cir) {
            System.out.println("after scale");
        }
    }

    @Mixin(Target.class)
    public static class CaptureLocals {
        @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;doWork(I)V"), locals = LocalCapture.CAPTURE_FAILHARD)
        private void seeLocal(CallbackInfo ci, int local) {
            System.out.println(local);
        }
    }

    @Mixin(Target.class)
    public static class ConstantInt {
        @ModifyConstant(method = "tick", constant = @Constant(intValue = 20))
        private int moreCounter(int original) {
            return 40;
        }
    }

    @Mixin(Target.class)
    public static class ConstantFloat {
        @ModifyConstant(method = "speed", constant = @Constant(floatValue = 0.5F))
        private float half(float original) {
            return original / 2;
        }
    }

    @Mixin(Target.class)
    public static class ConstantByType {
        @ModifyConstant(method = "getValue")
        private int anyInt(int original) {
            return original + 1;
        }
    }

    @Mixin(Target.class)
    public static class VariableByOrdinal {
        @ModifyVariable(method = "getValue", at = @At("STORE"), ordinal = 1)
        private int bumpResult(int result) {
            return result + 1;
        }
    }

    @Mixin(Target.class)
    public static class VariableByName {
        @ModifyVariable(method = "getValue", at = @At("STORE"), name = "s")
        private String shout(String s) {
            return s + "!";
        }
    }

    @Mixin(Target.class)
    public static class VariableHeadArg {
        @ModifyVariable(method = "speed", at = @At("HEAD"), argsOnly = true)
        private float doubleBase(float base) {
            return base * 2;
        }
    }

    @Mixin(Target.class)
    public static class VariableAmbiguous {
        @ModifyVariable(method = "getValue", at = @At("STORE"))
        private int ambiguous(int value) {
            return value;
        }
    }

    @Mixin(Target.class)
    public static class RedirectCall {
        @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;isReady()Z"))
        private boolean checkReady(Target.Helper helper) {
            return helper != null && helper.hashCode() > 0;
        }
    }

    @Mixin(Target.class)
    public static class RedirectStatic {
        @Redirect(method = "speed", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;scale(I)I"))
        private static int scaleMore(int value) {
            return value + 1000;
        }
    }

    @Mixin(Target.class)
    public static class RedirectCapture {
        @Redirect(method = "speed", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;scale(I)I"))
        private int scaleWithArgs(int value, float base, int mult) {
            return value + mult;
        }
    }

    @Mixin(Target.class)
    public static class RedirectField {
        @Redirect(method = "tick", at = @At(value = "FIELD", target = "Lfixtures/Target;counter:I", opcode = Opcodes.PUTFIELD))
        private void setCounter(Target target, int value) {
            System.out.println(value);
        }
    }

    @Mixin(Target.class)
    public static class InjectHead {
        @Inject(method = "tick", at = @At("HEAD"))
        private void onTick(CallbackInfo ci) {
            System.out.println("head");
        }
    }

    @Mixin(Target.class)
    public static class ModArg {
        @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;doWork(I)V"))
        private int modArg(int x) {
            return x * 10;
        }
    }

    @Mixin(Target.class)
    public static class ExprValue {
        @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;isReady()Z"))
        private boolean ready(boolean original, @Local int local) {
            return original && local > 3;
        }
    }

    @Mixin(Target.class)
    public static class ReturnValue {
        @ModifyReturnValue(method = "getValue", at = @At("RETURN"))
        private int doubled(int original) {
            return original * 2;
        }
    }

    @Mixin(Target.class)
    public static class WrapCondition {
        @WrapWithCondition(method = "tick", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;doWork(I)V"))
        private boolean onlyIf(Target.Helper helper, int x) {
            return x > 2;
        }
    }

    @Mixin(Target.class)
    public static class WrapOp {
        @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;doWork(I)V"))
        private void wrapWork(Target.Helper helper, int x, Operation<Void> original) {
            original.call(helper, x + 1);
        }
    }

    @Mixin(Target.class)
    public static class WrapWhole {
        @WrapMethod(method = "getValue")
        private int wrapValue(int input, Operation<Integer> original) {
            return original.call(input) + 1;
        }
    }

    @Mixin(Target.class)
    public static class OverwriteValue {
        @Overwrite
        public int getValue(int input) {
            return input + input;
        }
    }

    @Mixin(Target.class)
    public static class InjectConstant {
        @Inject(method = "tick", at = @At(value = "CONSTANT", args = "intValue=20"))
        private void beforeTwenty(CallbackInfo ci) {
            System.out.println("const");
        }
    }

    @Mixin(Target.class)
    public static class ExprConstant {
        @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=20"))
        private int bigger(int original) {
            return original * 2;
        }
    }

    @Mixin(Target.class)
    public static class InjectJump {
        @Inject(method = "tick", at = @At(value = "JUMP", opcode = Opcodes.IFEQ))
        private void beforeJump(CallbackInfo ci) {
            System.out.println("jump");
        }
    }

    @Mixin(Target.class)
    public static class InjectShiftBy {
        @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;isReady()Z", shift = At.Shift.BY, by = 2))
        private void shifted(CallbackInfo ci) {
            System.out.println("shifted");
        }
    }

    @Mixin(Target.class)
    public static class InjectCtorHead {
        @Inject(method = "<init>", at = @At("CTOR_HEAD"))
        private void ctorHead(CallbackInfo ci) {
            System.out.println("ctor");
        }
    }

    @Mixin(Target.class)
    public static class ArgFromAll {
        @ModifyArg(method = "report", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;send(Ljava/lang/String;I)V"), index = 1)
        private int withKey(String key, int value) {
            return value + key.length();
        }
    }

    @Mixin(Target.class)
    public static class ArgsObject {
        @ModifyArgs(method = "report", at = @At(value = "INVOKE", target = "Lfixtures/Target$Helper;send(Ljava/lang/String;I)V"))
        private void changeArgs(Args args) {
            args.set(1, 99);
        }
    }

    @Mixin(Target.class)
    public static class ReturnWithArgs {
        @ModifyReturnValue(method = "getValue", at = @At("RETURN"))
        private int plusInput(int original, int input) {
            return original + input;
        }
    }

    @Mixin(Target.class)
    public static class ClashFirst {
        @ModifyReturnValue(method = "getValue", at = @At("RETURN"))
        private int mod(int original) {
            return original * 2;
        }
    }

    @Mixin(Target.class)
    public static class ClashSecond {
        @ModifyReturnValue(method = "getValue", at = @At("RETURN"))
        private int mod(int original) {
            return original - 7;
        }
    }

    @Mixin(Target.class)
    public static abstract class WithInterface implements Runnable {
        @Override
        public void run() {
        }
    }

    @Mixin(Target.class)
    public static class CtorWithArgs {
        @Unique
        private String tag = "mixin";

        CtorWithArgs(int unused) {
            super();
        }
    }

    @Mixin(Target.class)
    public static class UniqueField {
        @Unique
        private int bonus = 42;

        @Unique
        public int bonus() {
            return bonus;
        }
    }
}
