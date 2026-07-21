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
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class Mixins {
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
    public static class UniqueField {
        @Unique
        private int bonus = 42;

        @Unique
        public int bonus() {
            return bonus;
        }
    }
}
