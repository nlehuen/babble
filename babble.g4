grammar babble;

file: declaration* EOF;

declaration: _package
           | _class
           | function
           | imp;

_package: PACKAGE ID '(' declaration* ')';

PACKAGE: 'package'; 
 
_class: CLASS ID templateDeclaration? '(' declaration* ')';

CLASS: 'class';

function: 'function' ID parametersDeclaration returnTypeDeclaration '->' '(' statement* ')';

imp: 'import' ID ( 'as' ID )?;

parametersDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')'
                     | '(' ')';

templateDeclaration: '(' parameterDeclaration (',' parameterDeclaration)* ')';

returnTypeDeclaration: ':' returnTypeDeclaration
                     | ;

parameterDeclaration: ID ':' type;

statement: ifStatement
         | loopStatement
         | callStatement
         | defStatement
         | assignStatement;

ifStatement: 'if' expression '(' statement* ')'
             ( 'else' '(' statement* ')' )?;

callStatement: ID callParameters;

callParameters: '(' callParameter (',' callParameter)* ')'
              | '(' ')';

callParameter: (ID ':')? expression;

defStatement: 'def' ID (':' type)? ('=' expression)?;

assignStatement: ID '=' expression;

loopStatement: 'while' expression '(' statement* ')';

expression: '(' expression ')'
          | expression ('*' | '/') expression
          | expression ('+' | '-') expression
          | callStatement
          | ID
          | INT
          | FLOAT;

type: ID
    | ID '(' type (',' type)* ')';

ID: [a-zA-Z] [a-zA-Z0-9]*;
INT: [0-9]+;
FLOAT: [0-9]* '.' [0-9]+ ('E' [0-9]+)?;