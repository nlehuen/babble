grammar Babble;

file: sequence EOF;

expression:
          PACKAGE name=ID '(' packageBlock=sequence ')'      # packageExpression
          | IF test=expression THEN thenBlock=block
             ( ELSE elseBlock=block)?                        # ifExpression
          | DEF name=ID (':' type)? ('=' value=expression)?  # defExpression
          | RETURN expression                                # returnExpression
          | WHILE test=expression THEN whileBlock=block      # whileExpression
          | expression '.' ID                                # selector
          | expression callParameters                        # call
          | block                                            # blockExpression
          | '(' expression ')'                               # parenthesis
          | NOT expression                                   # booleanNot
          | left=expression op=('*' | '/') right=expression  # binaryOp
          | left=expression op=('+' | '-') right=expression  # binaryOp
          | left=expression op=('<' | '<=' | '=='
                       | '>=' | '>') right=expression        # binaryOp
          | left=expression op=AND right=expression          # booleanOp
          | left=expression op=OR right=expression           # booleanOp
          | functionType '->' block                          # functionLiteral
          | name=ID '=' value=expression                     # assignExpression
          | NULL                                             # null
          | BOOLEAN                                          # boolean
          | RECURSE                                          # recurse
          | ID                                               # id
          | INT                                              # integer
          | FLOAT                                            # double
          | STRING                                           # string
          ;

block: '(' ')'
     | '(' sequence ')';

sequence:
        | expression (';'? expression)*;

parametersDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')'
                     | '(' ')';

parameterDeclaration: ID (':' type)?;

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
GTE: '>=';
GT: '>';
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
ID: [_a-zA-Z] [_a-zA-Z0-9]*;
WS: [ \t\r\n]+ -> skip;
