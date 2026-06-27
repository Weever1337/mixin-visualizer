package fixtures;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class Mixins {
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
