package com.byd.extend;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Static, conservative recognizer. Never loads or executes system-server classes. */
public final class OemWakeAnalyzer {
    public static final int VERSION = 1;
    private static final String POWER = "Lcom/android/server/power/PowerManagerService";
    private static final Object REASON = new Object(), QUICK = new Object();
    private static final int MAX_DEX_BYTES = 48 * 1024 * 1024;
    private final Map<String, Body> methods = new LinkedHashMap<>();
    private final List<String> roots = new ArrayList<>();
    private final long deadlineNanos;
    private long totalBytes;

    public OemWakeAnalyzer(long budgetMs) {
        deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
    }

    public static final class Result {
        public final int reason;
        public final String evidence;
        Result(int reason, String evidence) { this.reason = reason; this.evidence = evidence; }
    }

    public Result analyze(List<File> jars) throws IOException {
        Set<String> classes = new HashSet<>();
        for (File jar : jars) {
            check();
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("classes(?:[2-9][0-9]*)?\\.dex")) continue;
                    if (entry.getSize() < 112 || entry.getSize() > MAX_DEX_BYTES)
                        throw new IOException("DEX size unsupported: " + jar + "!" + entry.getName() + " size=" + entry.getSize());
                    totalBytes += entry.getSize();
                    if (totalBytes > 256L * 1024 * 1024) throw new IOException("System DEX byte budget exceeded");
                    byte[] bytes;
                    try (InputStream in = zip.getInputStream(entry)) {
                        bytes = new byte[(int) entry.getSize()];
                        int offset = 0;
                        while (offset < bytes.length) {
                            check();
                            int n = in.read(bytes, offset, bytes.length - offset);
                            if (n < 0) throw new EOFException("Truncated DEX: " + jar + "!" + entry.getName());
                            offset += n;
                        }
                        if (in.read() != -1) throw new IOException("DEX exceeds declared size: " + entry.getName());
                    }
                    // Copy only relevant instructions; do not retain entire DEX buffers across entries.
                    for (ClassDef c : new DexBackedDexFile(Opcodes.getDefault(), bytes).getClasses()) {
                        check();
                        if (!c.getType().startsWith(POWER)) continue;
                        if (!classes.add(c.getType())) throw new IOException("Ambiguous system class: " + c.getType());
                        for (Method m : c.getMethods()) {
                            if (m.getImplementation() == null) continue;
                            Body body = new Body(m, jar.getPath() + "!" + entry.getName());
                            methods.put(key(m), body);
                            if ("Landroid/os/IPowerManager$Stub;".equals(c.getSuperclass())
                                    && "wakeUp".equals(m.getName()) && "V".equals(m.getReturnType())
                                    && m.getParameterTypes().toString().equals("[J, I, Ljava/lang/String;, Ljava/lang/String;]"))
                                roots.add(key(m));
                        }
                    }
                }
            } catch (RuntimeException e) { throw new IOException("Cannot decode system DEX in " + jar, e); }
        }
        if (roots.size() != 1) throw new IOException("Expected one implemented IPowerManager.wakeUp entry, found=" + roots.size());
        ArrayDeque<Call> queue = new ArrayDeque<>();
        queue.add(new Call(roots.get(0), 3, roots.get(0)));
        Set<String> visited = new HashSet<>();
        List<Result> results = new ArrayList<>();
        while (!queue.isEmpty()) {
            check();
            Call call = queue.remove();
            if (!visited.add(call.method + ":" + call.word)) continue;
            if (visited.size() > 64) throw new IOException("Wake call graph exceeds analysis budget");
            Body body = methods.get(call.method);
            if (body == null) continue;
            Object[][] values = flow(body, body.registers - body.arguments + call.word);
            for (int i = 0; i < body.code.size(); i++) {
                if (values[i] == null) continue;
                Instruction insn = body.code.get(i);
                MethodReference ref = invoked(insn);
                if (ref != null && methods.containsKey(key(ref))) {
                    int[] regs = arguments(insn);
                    Body target = methods.get(key(ref));
                    if (regs.length != target.arguments) throw new IOException("Call argument width mismatch: " + key(ref));
                    for (int a = 0; a < regs.length; a++) if (values[i][regs[a]] == REASON)
                        queue.add(new Call(key(ref), a, call.path + " -> " + key(ref)));
                }
                Result found = guard(body, values, i, call.path);
                if (found != null) results.add(found);
            }
        }
        if (results.size() != 1) throw new IOException("Unique QuickBoot wake guard not established; matches=" + results.size());
        return results.get(0);
    }

    private Result guard(Body b, Object[][] states, int at, String path) throws IOException {
        Instruction insn = b.code.get(at);
        if (insn.getOpcode() != Opcode.IF_EQ && insn.getOpcode() != Opcode.IF_NE) return null;
        TwoRegisterInstruction pair = (TwoRegisterInstruction) insn;
        Object a = states[at][pair.getRegisterA()], c = states[at][pair.getRegisterB()];
        Object literal = a == REASON ? c : c == REASON ? a : null;
        if (!(literal instanceof Integer) || (Integer) literal < 0) return null;
        int branch = b.target(at), next = at + 1;
        int accepted = insn.getOpcode() == Opcode.IF_EQ ? branch : next;
        int rejected = insn.getOpcode() == Opcode.IF_EQ ? next : branch;
        boolean quickGuard = false;
        for (int i = 0; i < at; i++) {
            if (states[i] == null || !(b.code.get(i) instanceof TwoRegisterInstruction)) continue;
            Opcode op = b.code.get(i).getOpcode();
            if (op != Opcode.IF_EQ && op != Opcode.IF_NE) continue;
            TwoRegisterInstruction q = (TwoRegisterInstruction) b.code.get(i);
            Object x = states[i][q.getRegisterA()], y = states[i][q.getRegisterB()];
            if (!((x == QUICK && Integer.valueOf(1).equals(y)) || (y == QUICK && Integer.valueOf(1).equals(x)))) continue;
            int enabled = op == Opcode.IF_EQ ? b.target(i) : i + 1;
            int disabled = op == Opcode.IF_EQ ? i + 1 : b.target(i);
            if (disabled == accepted && straightTo(b, enabled, at)) quickGuard = true;
        }
        if (!quickGuard || !rejects(b, states[at], rejected)) return null;
        // The allowed branch must lead to a real display-enable operation, not just a log/constant.
        boolean enables = false;
        for (int i : reachable(b, accepted)) {
            MethodReference ref = invoked(b.code.get(i));
            if (ref != null && key(ref).equals(POWER + ";->nativeSetScreenState[Z]V")) {
                int[] args = arguments(b.code.get(i));
                if (states[i] != null && args.length == 1 && Integer.valueOf(1).equals(states[i][args[0]])) enables = true;
            }
        }
        if (!enables) return null;
        return new Result((Integer) literal, b.source + " " + path + " guard_pc=0x"
                + Integer.toHexString(b.address[at]) + " property=sys.quickboot.enable equal_allows_native_screen_on unequal_returns_without_wake");
    }

    private boolean rejects(Body b, Object[] initial, int start) throws IOException {
        Object[] state = initial.clone();
        int at = start;
        Set<Integer> seen = new HashSet<>();
        while (at >= 0 && at < b.code.size() && seen.add(at) && seen.size() <= 80) {
            check();
            Instruction i = b.code.get(at);
            if (i.getOpcode() == Opcode.RETURN)
                return Integer.valueOf(0).equals(state[((OneRegisterInstruction) i).getRegisterA()]);
            if (i.getOpcode() == Opcode.RETURN_VOID) return b.key.endsWith("]V");
            if (i.getOpcode().name().startsWith("GOTO")) { at = b.target(at); continue; }
            if (i instanceof OffsetInstruction || i.getOpcode() == Opcode.THROW || i.getOpcode() == Opcode.RETURN_VOID) return false;
            MethodReference ref = invoked(i);
            if (ref != null && !ref.getDefiningClass().equals("Ljava/lang/StringBuilder;")
                    && !ref.getDefiningClass().equals("Landroid/util/Slog;")
                    && !ref.getDefiningClass().equals("Landroid/util/Log;")) return false;
            String op = i.getOpcode().name();
            if (op.startsWith("IPUT") || op.startsWith("SPUT") || op.startsWith("APUT")) return false;
            transfer(i, state);
            at++;
        }
        return false;
    }

    private static boolean straightTo(Body b, int from, int to) {
        if (from > to) return false;
        for (int i = from; i < to; i++) {
            Instruction insn = b.code.get(i);
            String op = insn.getOpcode().name();
            if (!(op.startsWith("CONST") || op.startsWith("MOVE") || insn.getOpcode() == Opcode.NOP)) return false;
        }
        return true;
    }

    private Object[][] flow(Body b, int reasonRegister) throws IOException {
        Object[][] states = new Object[b.code.size()][];
        if (states.length == 0) return states;
        states[0] = new Object[b.registers + 1]; // final slot is the invoke result
        states[0][reasonRegister] = REASON;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        int steps = 0;
        while (!queue.isEmpty()) {
            check();
            if (++steps > 100_000) throw new IOException("Wake dataflow budget exceeded: " + b.key);
            int at = queue.remove();
            Object[] out = states[at].clone();
            transfer(b.code.get(at), out);
            for (int next : b.successors(at)) {
                if (states[next] == null) { states[next] = out.clone(); queue.add(next); }
                else {
                    boolean changed = false;
                    for (int r = 0; r < out.length; r++) if (states[next][r] != null && !Objects.equals(states[next][r], out[r])) {
                        states[next][r] = null; changed = true;
                    }
                    if (changed) queue.add(next);
                }
            }
        }
        return states;
    }

    private static void transfer(Instruction i, Object[] state) {
        String op = i.getOpcode().name();
        int result = state.length - 1;
        if (op.startsWith("MOVE_RESULT")) state[((OneRegisterInstruction) i).getRegisterA()] = state[result];
        else if (op.startsWith("MOVE") && i instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction m = (TwoRegisterInstruction) i;
            state[m.getRegisterA()] = state[m.getRegisterB()];
        } else if (op.startsWith("CONST_STRING"))
            state[((OneRegisterInstruction) i).getRegisterA()] = ((StringReference) ((ReferenceInstruction) i).getReference()).getString();
        else if (op.startsWith("CONST") && i instanceof NarrowLiteralInstruction && !op.contains("WIDE"))
            state[((OneRegisterInstruction) i).getRegisterA()] = ((NarrowLiteralInstruction) i).getNarrowLiteral();
        else if (i.getOpcode().setsRegister() && i instanceof OneRegisterInstruction)
            state[((OneRegisterInstruction) i).getRegisterA()] = null;
        if (i.getOpcode().setsWideRegister() && i instanceof OneRegisterInstruction)
            state[((OneRegisterInstruction) i).getRegisterA() + 1] = null;
        MethodReference ref = invoked(i);
        if (ref != null) {
            int[] args = arguments(i);
            state[result] = key(ref).equals("Landroid/os/SystemProperties;->getInt[Ljava/lang/String;, I]I")
                    && args.length == 2 && "sys.quickboot.enable".equals(state[args[0]]) ? QUICK : null;
        } else if (!op.startsWith("MOVE_RESULT")) state[result] = null;
    }

    private Set<Integer> reachable(Body b, int start) throws IOException {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            check();
            int i = todo.remove();
            if (seen.add(i)) for (int next : b.successors(i)) todo.add(next);
        }
        return seen;
    }

    private void check() throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadlineNanos)
            throw new IOException("OEM wake analysis cancelled or deadline exceeded");
    }

    private static MethodReference invoked(Instruction i) {
        return i.getOpcode().name().startsWith("INVOKE") && i instanceof ReferenceInstruction
                && ((ReferenceInstruction) i).getReference() instanceof MethodReference
                ? (MethodReference) ((ReferenceInstruction) i).getReference() : null;
    }

    private static int[] arguments(Instruction i) {
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            int[] out = new int[r.getRegisterCount()];
            for (int n = 0; n < out.length; n++) out[n] = r.getStartRegister() + n;
            return out;
        }
        FiveRegisterInstruction r = (FiveRegisterInstruction) i;
        return Arrays.copyOf(new int[]{r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG()}, r.getRegisterCount());
    }

    private static String key(MethodReference m) {
        return m.getDefiningClass() + "->" + m.getName() + m.getParameterTypes() + m.getReturnType();
    }

    private static final class Call {
        final String method, path; final int word;
        Call(String method, int word, String path) { this.method = method; this.word = word; this.path = path; }
    }

    private static final class Body {
        final String key, source;
        final int registers, arguments;
        final List<Instruction> code = new ArrayList<>();
        final int[] address;
        final Map<Integer, Integer> index = new HashMap<>();
        Body(Method m, String source) throws IOException {
            this.key = key(m); this.source = source;
            registers = m.getImplementation().getRegisterCount();
            int args = (m.getAccessFlags() & 8) == 0 ? 1 : 0;
            for (CharSequence p : m.getParameterTypes()) args += p.toString().equals("J") || p.toString().equals("D") ? 2 : 1;
            arguments = args;
            for (Instruction i : m.getImplementation().getInstructions()) {
                if (code.size() >= 20_000) throw new IOException("Method instruction budget exceeded: " + key);
                code.add(ImmutableInstruction.of(i));
            }
            address = new int[code.size()];
            int pc = 0;
            for (int i = 0; i < code.size(); i++) { address[i] = pc; index.put(pc, i); pc += code.get(i).getCodeUnits(); }
        }
        int target(int at) throws IOException {
            Integer i = index.get(address[at] + ((OffsetInstruction) code.get(at)).getCodeOffset());
            if (i == null) throw new IOException("Invalid DEX branch in " + key);
            return i;
        }
        int[] successors(int at) throws IOException {
            String op = code.get(at).getOpcode().name();
            if (op.startsWith("RETURN") || op.equals("THROW")) return new int[0];
            if (op.startsWith("GOTO")) return new int[]{target(at)};
            if (op.startsWith("IF_")) return new int[]{target(at), at + 1};
            if (op.equals("PACKED_SWITCH") || op.equals("SPARSE_SWITCH")) {
                Instruction payload = code.get(target(at));
                if (!(payload instanceof SwitchPayload)) throw new IOException("Invalid switch payload: " + key);
                List<? extends SwitchElement> elements = ((SwitchPayload) payload).getSwitchElements();
                int[] next = new int[elements.size() + 1];
                next[0] = at + 1;
                for (int n = 0; n < elements.size(); n++) {
                    Integer i = index.get(address[at] + elements.get(n).getOffset());
                    if (i == null) throw new IOException("Invalid switch target: " + key);
                    next[n + 1] = i;
                }
                return next;
            }
            return at + 1 < code.size() ? new int[]{at + 1} : new int[0];
        }
    }
}
