package fixtures;

public class Target {
    public static class Helper {
        public boolean isReady() { return true; }
        public void doWork(int x) {}
        public static int scale(int v) { return v * 3; }
    }

    private int counter;
    private Helper helper = new Helper();

    public void tick() {
        int local = 5;
        if (helper.isReady()) {
            helper.doWork(local);
        }
        counter += 20;
    }

    public int getValue(int input) {
        int result = input * 2;
        String s = "abc";
        result += s.length();
        return result;
    }

    public float speed(float base, int mult) {
        float f = base * 0.5f;
        int i = Helper.scale(mult);
        return f * i;
    }
}
