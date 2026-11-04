import org.codehaus.groovy.control.*
int bad = 0
args.each { f ->
    def cu = new CompilationUnit(new CompilerConfiguration())
    cu.addSource(new File(f))
    try {
        cu.compile(Phases.CANONICALIZATION)
        println "OK    ${f}"
    } catch (MultipleCompilationErrorsException e) {
        def msgs = e.errorCollector.errors.collect { it.cause?.message ?: it.toString() }
        def real = msgs.findAll { !(it =~ /unable to resolve class groovyx\.net\.http/) }
        if (real) { bad++; println "FAIL  ${f}"; real.each { println "      " + it.readLines().take(3).join(' | ') } }
        else println "OK    ${f} (only Hubitat-provided classes unresolved)"
    }
}
System.exit(bad ? 1 : 0)
