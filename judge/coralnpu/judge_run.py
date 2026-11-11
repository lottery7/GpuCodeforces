# The judge's side of one `coral-npu` test, a Monitor command of Renode (IronPython 2.7): it plays the host of the
# Coral NPU, as the integration guide of the core describes it. It loads the program, puts the inputs of the test
# into the data memory, releases the core from reset, waits until it halts and saves the outputs.
#
# The manifest is written by the executor (CoralNpuBackend), one entry per line, numbers in decimal:
#   nonce <text>                 prefix of the result line
#   limit <instructions>         the run is stopped soon after this many
#   elf <file>                   the program
#   word <address> <value>       a 32-bit word of the arguments
#   load <address> <bytes> <file>  an input array
#   fill <address> <bytes> <byte>  an output array before the run
#   dump <address> <bytes> <file>  an output array after the run
#
# The result is one line on stdout: `<nonce> halted|limit|fault <instructions>` or
# `<nonce> trap <instructions> <cause>`. The program has no way to print: nothing of the machine reaches stdout.
import traceback

from Antmicro.Renode.Peripherals.CPU import CoralNPU

# RvvCoreMini: VLEN is 128 bits, elements are up to 32 bits wide (Zve32x, Zve32f)
VLEN = 128
ELEN = 32
# registers of the core's control block, relative to the core's address space (0 on this machine)
RESET_CONTROL = 0x30000
POLL_MS = 1


def mc_judge_run(manifest):
    try:
        report = _judge_run(manifest)
        code = 0
    except:
        # no result line: the executor reports the output it kept as its own failure
        report = "judge_run failed: " + traceback.format_exc()
        code = 3
    # Straight to stdout and on a line of its own: the Monitor's console ends its lines with "\n\r", which would
    # leave a carriage return in front of the nonce.
    System.Console.Out.Write("\n" + report + "\n")
    System.Console.Out.Flush()
    # A core that faulted on a memory access never returns from the translation library, and an orderly shutdown
    # would wait for it: the process ends here.
    System.Environment.Exit(code)


def _judge_run(manifest):
    machine = self.Machine
    sysbus = machine["sysbus"]
    npu = machine["sysbus.npu"]
    cpu = machine["sysbus." + CoralNPU.CoralNPU_RVV.__name__]
    cpu.VectorRegisterLength = VLEN
    cpu.VectorElementMaxWidth = ELEN

    nonce, limit, dumps = None, None, []
    for line in open(manifest):
        entry = line.split()
        if not entry:
            continue
        kind = entry[0]
        if kind == "nonce":
            nonce = entry[1]
        elif kind == "limit":
            limit = int(entry[1])
        elif kind == "elf":
            sysbus.LoadELF(entry[1])
        elif kind == "word":
            sysbus.WriteDoubleWord(int(entry[1]), int(entry[2]))
        elif kind == "load":
            data = System.IO.File.ReadAllBytes(entry[3])
            if data.Length != int(entry[2]):
                raise Exception("%s has %d bytes, the manifest says %s" % (entry[3], data.Length, entry[2]))
            sysbus.WriteBytes(data, int(entry[1]))
        elif kind == "fill":
            sysbus.WriteBytes(System.Array[System.Byte]([int(entry[3])] * int(entry[2])), int(entry[1]))
        elif kind == "dump":
            dumps.append((int(entry[1]), int(entry[2]), entry[3]))
        else:
            raise Exception("unknown manifest entry '%s'" % kind)
    if nonce is None or limit is None:
        raise Exception("the manifest has no nonce or no limit")

    # Nothing handles traps on this machine, so the first one ends the run. A bad memory access is not a trap in
    # this model: the core raises its FAULT status instead.
    traps = []

    def on_trap(cause):
        traps.append(int(cause))
        cpu.IsHalted = True

    cpu.AddHookAtInterruptBegin(System.Action[System.UInt64](on_trap))

    # the boot sequence of the integration guide: open the clock gate, then release the reset
    sysbus.WriteDoubleWord(RESET_CONTROL, 1)
    sysbus.WriteDoubleWord(RESET_CONTROL, 0)
    emulationManager.CurrentEmulation.StartAll()
    state = None
    while state is None:
        if traps:
            state = "trap"
        elif npu.CoreFaulted:
            state = "fault"
        elif npu.CoreHalted:
            state = "halted"
        elif cpu.ExecutedInstructions > limit:
            state = "limit"
        else:
            System.Threading.Thread.Sleep(POLL_MS)
    # a halted core executes nothing more: the count is exact
    instructions = cpu.ExecutedInstructions
    if state == "halted":
        for address, count, path in dumps:
            data = sysbus.ReadBytes(address, count) if count > 0 else System.Array[System.Byte]([])
            System.IO.File.WriteAllBytes(path, data)
    result = "%s %s %d" % (nonce, state, instructions)
    return result + " %d" % traps[0] if state == "trap" else result
