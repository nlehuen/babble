grammar Babble;

@lexer::members {
    // The core type names are keywords, except as member names : after '.' or '?.', they are
    // identifiers, so that Java methods such as List.set, Stream.map or File.list stay callable.
    private int lastDefaultType = -1;

    @Override
    public Token nextToken() {
        Token token = super.nextToken();
        if (token.getChannel() == Token.DEFAULT_CHANNEL) {
            if ((lastDefaultType == DOT || lastDefaultType == SAFE_DOT) && isCoreTypeKeyword(token.getType())
                    && token instanceof WritableToken writable) {
                writable.setType(ID);
            }
            lastDefaultType = token.getType();
        }
        return token;
    }

    @Override
    public void reset() {
        super.reset();
        lastDefaultType = -1;
    }

    public static boolean isCoreTypeKeyword(int type) {
        return type >= BYTE_KW && type <= VOID_KW;
    }
}

@parser::members {
    private static boolean isAxiomOrImpureWord(Token token) {
        if (token == null || token.getType() != ID) {
            return false;
        }
        String text = token.getText();
        return "axiom".equals(text) || "impure".equals(text);
    }

    private static boolean isParamModWord(Token token) {
        if (token == null || token.getType() != ID) {
            return false;
        }
        String text = token.getText();
        return "mut".equals(text) || "borrow".equals(text);
    }

    private static boolean isEffectOrAxiomWord(Token token) {
        if (token == null || token.getType() != ID) {
            return false;
        }
        return switch (token.getText()) {
            case "axiom", "impure", "pure", "hermetic", "mut", "borrow",
                 "deterministic", "nothrow", "idempotent", "commutative",
                 "associative", "monotone", "injective" -> true;
            default -> false;
        };
    }

    private boolean isParamModifier() {
        return isParamModWord(_input.LT(1)) && _input.LT(2).getType() == ID;
    }

    private boolean isFunctionLiteralStart() {
        int i = 1;
        if (!isEffectOrAxiomWord(_input.LT(i))) {
            return false;
        }
        while (isEffectOrAxiomWord(_input.LT(i))) {
            i++;
        }
        if (_input.LT(i).getType() != LPAREN) {
            return false;
        }
        int depth = 0;
        int firstInside = i + 1;
        while (_input.LT(firstInside).getType() == NL) {
            firstInside++;
        }
        while (true) {
            int t = _input.LT(i).getType();
            if (t == Token.EOF) {
                Token first = _input.LT(firstInside);
                return first.getType() == PRIVATE || first.getType() == DEF || isParamModWord(first);
            }
            if (t == LPAREN) {
                depth++;
            } else if (t == RPAREN) {
                depth--;
                if (depth == 0) {
                    i++;
                    break;
                }
            }
            i++;
        }
        int after = _input.LT(i).getType();
        return after == ARROW || after == COLON;
    }

    private boolean isFunctionTypeStart() {
        int i = 1;
        if (!isEffectOrAxiomWord(_input.LT(i))) {
            return false;
        }
        while (isEffectOrAxiomWord(_input.LT(i))) {
            i++;
        }
        return _input.LT(i).getType() == LPAREN;
    }

    private boolean isDefEffectStart() {
        if (!isAxiomOrImpureWord(_input.LT(1)) || isFunctionLiteralStart()) {
            return false;
        }
        return _input.LT(2).getType() == ID;
    }

    private boolean isEffectDeclarationStart() {
        if (_input.LT(1).getType() == EXPORT) {
            return isAxiomOrImpureWord(_input.LT(2));
        }
        if (!isAxiomOrImpureWord(_input.LT(1)) || isFunctionLiteralStart()) {
            return false;
        }
        return _input.LT(2).getType() == ID;
    }

    /**
     * Whether the parentheses ahead hold type arguments only a type can have : a nullable
     * {@code ?} or a union {@code |}, as in {@code Box(int?)(null)}. Those with names only,
     * {@code Box(int)}, parse as a call, which the type checker tells from a type application.
     */
    private boolean isTypeApplicationStart() {
        if (_input.LA(1) != LPAREN) {
            return false;
        }
        int depth = 0;
        boolean typeOnly = false;
        for (int i = 1; ; i++) {
            int t = _input.LA(i);
            if (t == Token.EOF) {
                return false;
            }
            if (t == LPAREN) {
                depth++;
            } else if (t == RPAREN) {
                depth--;
                if (depth == 0) {
                    return typeOnly;
                }
            } else if (t == QUESTION || t == PIPE) {
                typeOnly = true;
            }
        }
    }

    private boolean isTargetEffectWord() {
        return isEffectOrAxiomWord(_input.LT(1)) && _input.LT(2).getType() == ID;
    }
}

file: sep? ({isEffectDeclarationStart()}? effectDeclaration | expression)
      (sep ({isEffectDeclarationStart()}? effectDeclaration | expression))* sep? EOF;

// Statements are separated by newlines or semicolons.
// Newlines are ignored only after tokens that require a continuation
// (operators, commas, '=', '->', 'then', ...), never between an
// expression and a following '(' : that would be ambiguous with a call.
sep: (';' | NL)+;

effectDeclaration: EXPORT? targetEffectPrefix effectTarget (ctorOpen='(' ctorClose=')')?;

effectTarget: (ID '(' STRING ')' | ID) ('.' ID)*;

effectPrefix: effectWord+;

effectWord: {isEffectOrAxiomWord(_input.LT(1))}? ID;

targetEffectPrefix: targetEffectWord+;

targetEffectWord: {isTargetEffectWord()}? ID;

expression:
          // "export" is only accepted at the top level of a file, which the analysis checks.
          EXPORT? PACKAGE name=ID NL* packageBlock=block      # packageExpression
          | IF test=expression THEN NL* thenBlock=block
                     (NL* ELSE NL* elseBlock=block)?          # ifExpression
          // "else if" chains, without a block around each "if" : elseIf is an if expression,
          // which the predicate makes sure of. As the alternative ends with it, it is parsed
          // at this alternative's precedence : "… else (c) + 1" adds to the whole chain.
          | IF test=expression THEN NL* thenBlock=block
                     NL* ELSE NL* {_input.LA(1) == IF}? elseIf=expression   # ifExpression
          | (EXPORT | PRIVATE)? DEF name=ID typeParameters? (':' type)?
                     ('=' NL* ({isDefEffectStart()}? targetEffectPrefix)? value=expression)?   # defExpression
          | WHILE test=expression THEN NL* whileBlock=block   # whileExpression
          // "value class" : the modifier is a contextual keyword, checked by the analysis.
          // No newline before the body : "class (a)" followed by "(b)" on the next line
          // would be ambiguous between a header and a body.
          | modifier=ID? CLASS parametersDeclaration? (IMPLEMENTS typeList)? classBlock=block
                (NL* STATIC NL* staticBlock=block)?           # classExpression
          | INTERFACE (EXTENDS typeList)? NL* interfaceBlock=block
                (NL* STATIC NL* staticBlock=block)?           # interfaceExpression
          | OBJECT (IMPLEMENTS typeList)? NL* createBlock=block   # objectExpression
          | THIS                                              # thisExpression
          | SUPER ('(' NL* qualifier=type NL* ')')?           # superExpression
          | {isFunctionLiteralStart()}? effectPrefix parametersDeclaration ( ':' type | ) '->' NL* functionBlock=block  # functionLiteral
          // A newline may come before the dot : a chain of calls can go on the next lines.
          | expression NL* ('.' | safe='?.') ID               # selector
          | ID                                                # selector
          // A core type name : as a value, its conversion function (int(x)), or what x is int tests.
          | coreTypeKeyword                                   # coreTypeName
          // The non-null assertion : e! is e, failing when it is null. No newline before '!'.
          | expression BANG                                   # nonNullAssertion
          // Explicit type arguments that only a type can have : Box(int?)(null), f(int | string).
          | expression {isTypeApplicationStart()}? typeArguments   # typeApplication
          | expression callParameters                         # call
          | block                                             # blockExpression
          | NOT expression                                    # booleanNot
          // Unary minus binds tighter than any binary operator : "-a * b" is "(-a) * b". A
          // newline before it separates : "a" then "-b" on the next line are two expressions.
          | '-' operand=expression                            # negate
          | left=expression op=('*' | '/') NL* right=expression   # binaryOp
          | left=expression op=('+' | '-') NL* right=expression   # binaryOp
          | left=expression AS NL* right=type                     # asExpression
          | left=expression op=('<' | '<=' | '==' | NEQ
                       | '>=' | '>') NL* right=expression         # binaryOp
          | left=expression IS NL* right=expression               # isExpression
          | left=expression op=AND NL* right=expression           # booleanOp
          | left=expression op=OR NL* right=expression            # booleanOp
          | parametersDeclaration ( ':' type | ) '->' NL* functionBlock=block  # functionLiteral
          // Compound assignment : "x += v" is "x = x + v" (and -=, *=, /=), with the target's
          // receiver evaluated once.
          | namespace=expression NL* '.' name=ID op=('=' | '+=' | '-=' | '*=' | '/=') NL* value=expression # assignExpression
          | name=ID op=('=' | '+=' | '-=' | '*=' | '/=') NL* value=expression   # assignExpression
          | NULL                                              # null
          | BOOLEAN                                           # boolean
          | RECURSE                                           # recurse
          | INT                                               # integer
          | LONG_LIT                                          # longLiteral
          | FLOAT_LIT                                         # floatLiteral
          | DOUBLE_LIT                                        # double
          | CHAR_LIT                                          # charLiteral
          | STRING                                            # string
          // "return" is a prefix alternative, so its operand is parsed at this
          // alternative's precedence. It must therefore come last, or "return a + b"
          // would parse as "(return a) + b".
          | RETURN expression                                 # returnExpression
          ;

block: '(' sep? ')'
     | '(' sep? ({isEffectDeclarationStart()}? effectDeclaration | expression)
       (sep ({isEffectDeclarationStart()}? effectDeclaration | expression))* sep? ')';

parametersDeclaration: '(' NL* parameterDeclaration (NL* ',' NL* parameterDeclaration)* NL* ')'
                     | '(' NL* ')';

// PRIVATE and DEF are only accepted in class headers, which the analysis checks.
parameterDeclaration: PRIVATE? DEF? ({isParamModifier()}? paramModifier)* ID (':' type)? ('=' NL* defaultValue=expression)?;

paramModifier: ID;

typeParameters: '(' NL* ID (NL* ',' NL* ID)* NL* ')';

typeArguments: '(' NL* type (NL* ',' NL* type)* NL* ')';

callParameters: '(' NL* callParameter (NL* ',' NL* callParameter)* NL* ')'
              | '(' NL* ')';

callParameter: (ID ':')? expression;

typeList: type (NL* ',' NL* type)*;

type: nullableType ('|' NL* nullableType)*;

nullableType: primaryType question='?'?;

primaryType: {isFunctionTypeStart()}? effectPrefix parametersDeclaration ( ':' type | )   # functionType
           | ID ('.' ID)* typeArguments?                                                  # simpleType
           | coreTypeKeyword typeArguments?                                               # coreType
           | parametersDeclaration ( ':' type | )                                         # functionType
           | NULL                                                                         # nullType
           | '(' NL* type NL* ')'                                                         # parenType
           ;

// The core type names (they can't be redefined).
coreScalarKeyword: BYTE_KW | SHORT_KW | INT_KW | LONG_KW | FLOAT_KW | DOUBLE_KW | BOOL_KW | CHAR_KW | STRING_KW;

coreCollectionKeyword: LIST_KW | SET_KW | SORTEDSET_KW | MAP_KW | SORTEDMAP_KW | ENTRY_KW;

coreTypeKeyword: coreScalarKeyword | coreCollectionKeyword | ANY_KW | VOID_KW;

// Tokens
// Numbers : 42 (int, or byte, short, long, float, double where one is expected), 42L (long),
// 1.5f (float), 1.5 or 1.5d (double).
INT: [0-9]+;
LONG_LIT: [0-9]+ [lL];
FLOAT_LIT: ([0-9]* '.' [0-9]+ EXPONENT? | [0-9]+ EXPONENT?) [fF];
DOUBLE_LIT: [0-9]* '.' [0-9]+ EXPONENT? [dD]? | [0-9]+ EXPONENT [dD]? | [0-9]+ [dD];
fragment EXPONENT: [eE] [+-]? [0-9]+;
// Escapes : \\ \" \' \n \t \r \b \f \0 and \uXXXX.
fragment ESCAPE: '\\' ([btnfr"'\\0] | 'u' HEX HEX HEX HEX);
fragment HEX: [0-9a-fA-F];
CHAR_LIT: '\'' (~['\\\r\n] | ESCAPE) '\'';
STRING: '"' (~[\\"] | ESCAPE)* '"';
PLUS: '+';
MINUS: '-';
MUL: '*';
DIV: '/';
LT: '<';
LTE: '<=';
EQ: '==';
NEQ: '<>' | '!=';
BANG: '!';
GTE: '>=';
GT: '>';
COLON: ':';
SEMICOLON: ';';
ASSIGN: '=';
PLUS_ASSIGN: '+=';
MINUS_ASSIGN: '-=';
MUL_ASSIGN: '*=';
DIV_ASSIGN: '/=';
DOT: '.';
SAFE_DOT: '?.';
ARROW: '->';
PIPE: '|';
QUESTION: '?';
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
PACKAGE: 'package';
IF: 'if';
THEN: 'then';
ELSE: 'else';
DEF: 'def';
RETURN: 'return';
WHILE: 'while';
AND: 'and';
OR: 'or';
NULL: 'null';
NOT: 'not';
BOOLEAN: 'true' | 'false';
RECURSE: 'recurse';
OBJECT: 'object';
CLASS: 'class';
INTERFACE: 'interface';
IMPLEMENTS: 'implements';
EXTENDS: 'extends';
IS: 'is';
AS: 'as';
THIS: 'this';
SUPER: 'super';
PRIVATE: 'private';
STATIC: 'static';
EXPORT: 'export';
// Core type names : keep them together and in this order (isCoreTypeKeyword).
BYTE_KW: 'byte';
SHORT_KW: 'short';
INT_KW: 'int';
LONG_KW: 'long';
FLOAT_KW: 'float';
DOUBLE_KW: 'double';
BOOL_KW: 'bool';
CHAR_KW: 'char';
STRING_KW: 'string';
LIST_KW: 'list';
SET_KW: 'set';
SORTEDSET_KW: 'sortedset';
MAP_KW: 'map';
SORTEDMAP_KW: 'sortedmap';
ENTRY_KW: 'entry';
ANY_KW: 'any';
VOID_KW: 'void';
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
// Comments are kept, on the hidden channel : the parser does not see them, the formatter does.
MULTILINECOMMENT: ';;(' .*? ';;)' -> channel(HIDDEN);
COMMENT: ';;' ~[\r\n]* -> channel(HIDDEN);
NL: '\r'? '\n';
WS: [ \t]+ -> skip;
