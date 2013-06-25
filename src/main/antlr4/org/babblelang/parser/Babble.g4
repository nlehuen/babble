grammar Babble;

file: expression (';'? expression)* EOF;

expression:
          PACKAGE name=ID packageBlock=block                 # packageExpression
          | IF test=expression THEN thenBlock=block
                               (ELSE elseBlock=block)?       # ifExpression
          | DEF name=ID (':' type)? ('=' value=expression)?  # defExpression
          | RETURN expression                                # returnExpression
          | WHILE test=expression THEN whileBlock=block      # whileExpression
          | OBJECT createBlock=block                         # objectExpression
          | expression '.' ID                                # selector
          | ID                                               # selector
          | expression callParameters                        # call
          | block                                            # blockExpression
          | NOT expression                                   # booleanNot
          | left=expression op=('*' | '/') right=expression  # binaryOp
          | left=expression op=('+' | '-') right=expression  # binaryOp
          | left=expression op=('<' | '<=' | '==' | NEQ
                       | '>=' | '>') right=expression        # binaryOp
          | left=expression op=AND right=expression          # booleanOp
          | left=expression op=OR right=expression           # booleanOp
          | functionType '->' functionBlock=block            # functionLiteral
          | name=ID '=' value=expression                     # assignExpression
          | NULL                                             # null
          | BOOLEAN                                          # boolean
          | RECURSE                                          # recurse
          | INT                                              # integer
          | FLOAT                                            # double
          | STRING                                           # string
          ;

block: '(' ')'
     | '(' expression (';'? expression)* ')';

parametersDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')'
                     | '(' ')';

parameterDeclaration: ID (':' type)? ('=' defaultValue=expression)?;

callParameters: '(' callParameter (',' callParameter)* ')'
              | '(' ')';

callParameter: (ID ':')? expression;

type: simpleType
    | type parametersDeclaration
    | functionType
    ;

simpleType: ID ('.' ID)*;

functionType: parametersDeclaration ( ':' type | );

// Tokens
INT: [0-9]+;
FLOAT: [0-9]* '.' [0-9]+ ('E' [0-9]+)?;
STRING: '"' (~[\\"]|'\\\\'|'\\"')*? '"';
PLUS: '+';
MINUS: '-';
MUL: '*';
DIV: '/';
LT: '<';
LTE: '<=';
EQ: '==';
NEQ: '<>' | '!=';
GTE: '>=';
GT: '>';
COLON: ':';
ASSIGN: '=';
DOT: '.';
ARROW: '->';
RPAREN: '(';
LPAREN: ')';
COMMA: ',';
PACKAGE: 'package' | 'paquet';
IF: 'if' | 'si';
THEN: 'then' | 'alors';
ELSE: 'else' | 'sinon';
DEF: 'def' | 'soit';
RETURN: 'return' | 'retourne';
WHILE: 'while' | 'tant' WS* 'que';
AND: 'and' | 'et';
OR: 'or' | 'ou';
NULL: 'null' | 'vide';
NOT: 'not' | 'non';
BOOLEAN: 'true' | 'false' | 'vrai' | 'faux';
RECURSE: 'recurse' | 'recurrence';
OBJECT: 'object' | 'objet';
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
MULTILINECOMMENT: ';;(' .*? ';;)' -> skip;
COMMENT: ';;' .*? '\n' -> skip;
WS: [ \t\r\n]+ -> skip;
