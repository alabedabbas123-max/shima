# Pinned parser sources

`parsers-0.25.0-java-0.23.5-kotlin-1.1.0.tar.gz` is a deterministic,
source-only repack of the following MIT-licensed npm distributions:

* `tree-sitter@0.25.0`: https://github.com/tree-sitter/tree-sitter
* `tree-sitter-java@0.23.5`: https://github.com/tree-sitter/tree-sitter-java
* `@tree-sitter-grammars/tree-sitter-kotlin@1.1.0`: https://github.com/tree-sitter-grammars/tree-sitter-kotlin

SHA-256: `03755eac17ee6b4481d60c74fb2464a13ce96156e2aec54253c8f46df54653b0`.
Only C sources, headers and the respective licence files are included; the
unused WebAssembly runtime was omitted. The unmodified upstream parser sources
are generated C and are needed to build the AAR without parser generators.
The licence notices are also packaged in the AAR under
`assets/shima-analyzer/THIRD_PARTY_NOTICES.txt`.
