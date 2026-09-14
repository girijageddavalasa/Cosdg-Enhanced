interface PaymentProcessor {
    int process(int amount);
}

class CardProcessor implements PaymentProcessor {
    @Override
    public int process(int amount) {
        if (amount > 1000) {
            return amount - 100;
        }
        return amount - 20;
    }
}

class CashProcessor implements PaymentProcessor {
    @Override
    public int process(int amount) {
        return amount + 10;
    }
}

class RewardProcessor extends CardProcessor {
    @Override
    public int process(int amount) {
        return super.process(amount) + 50;
    }
}

class CombinedGenerationExample {
    static PaymentProcessor selectProcessor(int mode) {
        if (mode == 1) {
            return new CardProcessor();
        } else if (mode == 2) {
            return new CashProcessor();
        }
        return new RewardProcessor();
    }

    static int calculate(int mode, String amountText, int divisor) {
        try {
            int amount = Integer.parseInt(amountText);

            if (amount < 0) {
                throw new IllegalArgumentException("amount must not be negative");
            }

            PaymentProcessor processor = selectProcessor(mode);
            int processed = processor.process(amount);
            int total = 0;

            for (int index = 0; index < 3; index++) {
                if (processed > index * 100) {
                    total += processed - index;
                } else {
                    total += index;
                }
            }

            if (divisor == 0) {
                throw new ArithmeticException("divisor must not be zero");
            }

            return total / divisor;
        } catch (NumberFormatException exception) {
            return -100;
        } catch (IllegalArgumentException exception) {
            return -200;
        } catch (ArithmeticException exception) {
            return -300;
        }
    }

    static int guardedLookup(int[] values, int index) {
        try {
            if (values == null) {
                throw new NullPointerException("values must not be null");
            }
            return values[index];
        } catch (NullPointerException exception) {
            return -400;
        } catch (ArrayIndexOutOfBoundsException exception) {
            return -500;
        }
    }

    static int runScenario(int mode, String amountText, int divisor, int lookupIndex) {
        int calculation = calculate(mode, amountText, divisor);
        int lookup = guardedLookup(new int[] { 5, 10, 15 }, lookupIndex);

        if (calculation >= 0 && lookup >= 0) {
            return calculation + lookup;
        }
        return calculation + lookup - 1;
    }

    public static void main(String[] args) {
        System.out.println(runScenario(1, "1200", 2, 1));
        System.out.println(runScenario(2, "invalid", 2, 1));
        System.out.println(runScenario(3, "-5", 2, 1));
        System.out.println(runScenario(1, "100", 0, 8));
    }
}
