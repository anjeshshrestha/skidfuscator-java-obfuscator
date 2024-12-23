package dev.skidfuscator.obfuscator.exempt.v2;

import dev.skidfuscator.obfuscator.exempt.Exclusion;
import dev.skidfuscator.obfuscator.exempt.ExclusionMap;
import dev.skidfuscator.obfuscator.exempt.ExclusionTester;
import dev.skidfuscator.obfuscator.exempt.ExclusionType;
import dev.skidfuscator.obfuscator.util.match.Match;
import lombok.Data;
import lombok.experimental.UtilityClass;
import org.mapleir.asm.ClassNode;
import org.mapleir.asm.FieldNode;
import org.mapleir.asm.MethodNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@UtilityClass
public class ExclusionParser {
    private static final Set<String> CLASS_TYPES = Set.of(
            "class", "interface", "abstract", "annotation", "enum"
    );

    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "volatile", "transient", "synchronized", "native", "strictfp"
    );

    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "([\\w$.]+)(?:<(.+)>)?(?:\\[\\])*"
    );

    @Data
    private static class ParsedMember {
        private final String type;              // method or field
        private final Set<String> modifiers;    // public, static, etc.
        private final String returnType;        // return type for methods
        private final String name;              // member name
        private final List<String> parameters;  // method parameters
        private final String containingClass;   // class this member belongs to
        private final boolean isInclusion;      // whether this member is an inclusion

        @Override
        public String toString() {
            return "ParsedMember{" +
                    "type='" + type + '\'' +
                    ", modifiers=" + modifiers +
                    ", returnType='" + returnType + '\'' +
                    ", name='" + name + '\'' +
                    ", parameters=" + parameters +
                    ", containingClass='" + containingClass + '\'' +
                    ", isInclusion=" + isInclusion +
                    '}';
        }
    }

    @Data
    private static class ParsedClass {
        private final String type;              // class, interface, etc.
        private final Set<String> modifiers;    // public, static, etc.
        private final String name;              // fully qualified name
        private final String extendsClass;      // extended class
        private final Set<String> interfaces;   // implemented interfaces
        private final List<ParsedMember> members; // class members
        private final boolean include;        // whether this class is an inclusion
    }

    /**
     * Main entry point for parsing exclusion patterns
     */
    public List<String> parsePattern(String input) {
        try {
            ParsedClass parsedClass = parseClass(input);
            return generateExclusions(parsedClass);
        } catch (Exception e) {
            throw new ExclusionParseException("Failed to parse exclusion pattern: " + input, e);
        }
    }

    /**
     * Parses a class declaration and its members
     */
    private ParsedClass parseClass(String input) {
        String[] lines = input.split("\n");
        if (lines.length == 0) {
            throw new ExclusionParseException("Empty input");
        }

        // Parse class header and body separately
        String header;
        String body = "";
        int braceIndex = input.indexOf("{");
        if (braceIndex != -1) {
            header = input.substring(0, braceIndex).trim();
            int closingBrace = input.lastIndexOf("}");
            if (closingBrace != -1) {
                body = input.substring(braceIndex + 1, closingBrace).trim();
            }
        } else {
            header = input.trim();
        }

        // Check for inclusion prefix
        boolean include = header.startsWith("!");
        if (include) {
            System.out.println("include: " + include);
            header = header.substring(1);
        }

        if (!header.startsWith("@")) {
            throw new ExclusionParseException("Class declaration must start with @ or !@");
        }

        // Split header into tokens
        List<String> tokens = tokenize(header.substring(1));
        if (tokens.isEmpty()) {
            throw new ExclusionParseException("Invalid class declaration");
        }

        //System.out.println("tokens: " + tokens);

        // Parse class type
        String classType = tokens.get(0);
        if (!CLASS_TYPES.contains(classType)) {
            throw new ExclusionParseException("Invalid class type: " + classType);
        }
        tokens.remove(0);

        // Parse modifiers and name
        Set<String> modifiers = new HashSet<>();
        String className = null;
        String extendsClass = null;
        Set<String> interfaces = new HashSet<>();

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            
            if (MODIFIERS.contains(token)) {
                modifiers.add(token);
            } else if (token.equals("extends")) {
                i++;
                if (i >= tokens.size()) {
                    throw new ExclusionParseException("'extends' must be followed by a class name");
                }
                extendsClass = tokens.get(i);
            } else if (token.equals("implements")) {
                i++;
                while (i < tokens.size() && !tokens.get(i).equals("{")) {
                    String impl = tokens.get(i);
                    if (!impl.equals(",")) {
                        interfaces.add(impl);
                    }
                    i++;
                }
                i--; // Adjust for the extra increment
            } else if (token.equals("{")) {
                break;
            } else if (className == null) {
                className = token;
            } else {
                throw new ExclusionParseException("Unexpected token: " + token);
            }
            i++;
        }

        if (className == null) {
            throw new ExclusionParseException("Class name is required");
        }

        // Parse members using state machine
        List<ParsedMember> members = new ArrayList<>();
        if (!body.isEmpty()) {
            MemberParserState state = new MemberParserState(body);
            while (state.hasMore()) {
                ParsedMember member = state.parseNextMember(className);
                if (member != null) {
                    System.out.println("member: " + member);
                    members.add(member);
                }
            }
        }

        return new ParsedClass(classType, modifiers, className, extendsClass, interfaces, members, include);
    }

    private static class MemberParserState {
        private final String input;
        private int position;
        private StringBuilder currentToken;
        private ParserState state;
        private int braceDepth;
        private boolean isInclusion;

        private enum ParserState {
            LOOKING_FOR_AT,
            READING_MEMBER,
            READING_NESTED_CLASS,
            SKIPPING_WHITESPACE
        }

        public MemberParserState(String input) {
            this.input = input;
            this.position = 0;
            this.currentToken = new StringBuilder();
            this.state = ParserState.LOOKING_FOR_AT;
            this.braceDepth = 0;
            this.isInclusion = false;
        }

        public boolean hasMore() {
            return position < input.length();
        }

        public ParsedMember parseNextMember(String className) {
            currentToken.setLength(0);
            isInclusion = false;
            
            while (hasMore()) {
                char c = input.charAt(position);
                switch (state) {
                    case LOOKING_FOR_AT:
                        if (c == '!') {
                            isInclusion = true;
                            position++;
                            System.out.println("isInclusion: " + isInclusion);
                            continue;
                        } else if (c == '@') {
                            state = ParserState.READING_MEMBER;
                            position++;
                        } else if (!Character.isWhitespace(c)) {
                            throw new ExclusionParseException("Expected @ symbol, found: " + c);
                        } else {
                            position++;
                        }
                        break;

                    case READING_MEMBER:
                        if (c == '@' || c == '!') {
                            // Found start of next member
                            String memberDecl = currentToken.toString().trim();
                            System.out.println("memberDecl: " + memberDecl);
                            if (!memberDecl.isEmpty()) {
                                return createParsedMember(memberDecl, className);
                            }
                            position--; // Back up to reprocess the @ or !
                            state = ParserState.LOOKING_FOR_AT;
                        } else if (c == '\n' || c == ';') {
                            // End of member declaration
                            String memberDecl = currentToken.toString().trim();
                            if (!memberDecl.isEmpty()) {
                                state = ParserState.LOOKING_FOR_AT;
                                position++;
                                return createParsedMember(memberDecl, className);
                            }
                            position++;
                        } else {
                            currentToken.append(c);
                            position++;
                        }
                        break;

                    case SKIPPING_WHITESPACE:
                        if (!Character.isWhitespace(c)) {
                            state = ParserState.LOOKING_FOR_AT;
                            continue;
                        }
                        position++;
                        break;
                }
            }

            // Handle last member if exists
            String memberDecl = currentToken.toString().trim();
            if (!memberDecl.isEmpty()) {
                return createParsedMember(memberDecl, className);
            }

            return null;
        }

        private ParsedMember createParsedMember(String declaration, String className) {
            return parseMember(declaration, className, -1, isInclusion);
        }

        private ParsedMember createNestedClassMember(String declaration, String className) {
            // Parse the nested class
            ParsedClass nestedClass = parseClass(declaration);
            
            // Convert to a special member
            return new ParsedMember(
                    "class",
                    nestedClass.getModifiers(),
                    null,
                    nestedClass.getName(),
                    Collections.emptyList(),
                    className,
                    isInclusion || nestedClass.isInclude()
            );
        }
    }

    /**
     * Parses a member (method or field) declaration
     */
    private ParsedMember parseMember(String input, String containingClass, int lineNum, boolean inclusion) {
        List<String> tokens = tokenize(input);
        if (tokens.isEmpty()) {
            throw new ExclusionParseException("Invalid member declaration", lineNum);
        }

        String memberType = tokens.get(0);
        if (!memberType.equals("method") && !memberType.equals("field") && !memberType.equals("class")) {
            throw new ExclusionParseException("Invalid member type: " + memberType, lineNum);
        }
        tokens.remove(0);

        Set<String> modifiers = new HashSet<>();
        String returnType = null;
        String name = null;
        List<String> parameters = new ArrayList<>();

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);

            if (MODIFIERS.contains(token)) {
                modifiers.add(token);
            } else if (token.startsWith("#")) {
                returnType = parseType(token.substring(1));
            } else if (token.startsWith("(")) {
                parameters.addAll(parseParameters(token));
            } else if (name == null) {
                name = token;
            } else {
                throw new ExclusionParseException("Unexpected token: " + token, lineNum);
            }
            i++;
        }

        if (name == null) {
            throw new ExclusionParseException("Member name is required", lineNum);
        }

        return new ParsedMember(memberType, modifiers, returnType, name, parameters, containingClass, inclusion);
    }

    /**
     * Generates exclusion patterns from parsed class
     */
    private List<String> generateExclusions(ParsedClass parsedClass) {
        List<String> exclusions = new ArrayList<>();

        // Generate class exclusion
        StringBuilder classPattern = new StringBuilder("@class ");
        
        // Add type-specific modifier
        if (!parsedClass.getType().equals("class")) {
            classPattern.append(parsedClass.getType()).append(" ");
        }

        // Add modifiers
        if (!parsedClass.getModifiers().isEmpty()) {
            classPattern.append(String.join(" ", parsedClass.getModifiers())).append(" ");
        }

        // Add inheritance
        if (parsedClass.getExtendsClass() != null) {
            classPattern.append("extends:").append(parsedClass.getExtendsClass()).append(" ");
        }

        // Add implementations
        for (String iface : parsedClass.getInterfaces()) {
            classPattern.append("implements:").append(iface).append(" ");
        }

        // Add class name
        classPattern.append(convertClassPattern(parsedClass.getName()));
        exclusions.add(classPattern.toString());

        // Generate member exclusions
        for (ParsedMember member : parsedClass.getMembers()) {
            StringBuilder memberPattern = new StringBuilder("@").append(member.getType()).append(" ");

            // Add modifiers
            if (!member.getModifiers().isEmpty()) {
                memberPattern.append(String.join(" ", member.getModifiers())).append(" ");
            }

            // Add containing class reference
            if (member.getType().equals("method")) {
                memberPattern.append("in:").append(member.getContainingClass()).append(" ");
            } else {
                memberPattern.append(member.getContainingClass()).append("#");
            }

            // Add return type
            if (member.getReturnType() != null) {
                memberPattern.append("#").append(member.getReturnType()).append(" ");
            }

            // Add name
            memberPattern.append(member.getName());

            // Add parameters for methods
            if (member.getType().equals("method") && !member.getParameters().isEmpty()) {
                memberPattern.append("(")
                        .append(String.join(", ", member.getParameters()))
                        .append(")");
            }

            exclusions.add(memberPattern.toString());
        }

        return exclusions;
    }

    /**
     * Utility methods
     */
    private List<String> tokenize(String input) {
        TokenizerState state = new TokenizerState(input);
        return state.tokenize();
    }

    private static class TokenizerState {
        private final String input;
        private int position;
        private final List<String> tokens;
        private final StringBuilder currentToken;
        private TokenizerMode mode;

        private enum TokenizerMode {
            NORMAL,
            IN_GENERICS,
            IN_PARENTHESES
        }

        public TokenizerState(String input) {
            this.input = input;
            this.position = 0;
            this.tokens = new ArrayList<>();
            this.currentToken = new StringBuilder();
            this.mode = TokenizerMode.NORMAL;
        }

        public List<String> tokenize() {
            while (position < input.length()) {
                char c = input.charAt(position);
                
                switch (mode) {
                    case NORMAL:
                        handleNormalMode(c);
                        break;
                        
                    case IN_GENERICS:
                        handleGenericsMode(c);
                        break;
                        
                    case IN_PARENTHESES:
                        handleParenthesesMode(c);
                        break;
                }
                
                position++;
            }
            
            // Add final token if exists
            addCurrentToken();
            
            return tokens.stream()
                    .filter(t -> !t.trim().isEmpty())
                    .collect(Collectors.toList());
        }

        private void handleNormalMode(char c) {
            if (c == '<') {
                mode = TokenizerMode.IN_GENERICS;
                currentToken.append(c);
            } else if (c == '(') {
                addCurrentToken();
                mode = TokenizerMode.IN_PARENTHESES;
                currentToken.append(c);
            } else if (c == ' ' || c == ',') {
                addCurrentToken();
                if (c == ',') {
                    tokens.add(",");
                }
            } else {
                currentToken.append(c);
            }
        }

        private void handleGenericsMode(char c) {
            currentToken.append(c);
            if (c == '>') {
                mode = TokenizerMode.NORMAL;
            }
        }

        private void handleParenthesesMode(char c) {
            currentToken.append(c);
            if (c == ')') {
                addCurrentToken();
                mode = TokenizerMode.NORMAL;
            }
        }

        private void addCurrentToken() {
            if (currentToken.length() > 0) {
                tokens.add(currentToken.toString());
                currentToken.setLength(0);
            }
        }
    }

    private String parseType(String type) {
        Matcher matcher = TYPE_PATTERN.matcher(type);
        if (!matcher.matches()) {
            throw new ExclusionParseException("Invalid type: " + type);
        }
        return type;
    }

    private List<String> parseParameters(String params) {
        String cleaned = params.substring(1, params.length() - 1);
        if (cleaned.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(cleaned.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private String convertClassPattern(String pattern) {
        if (pattern.contains("*")) {
            if (pattern.endsWith(".*")) {
                return "^" + pattern.substring(0, pattern.length() - 2).replace(".", "\\/") + ".*";
            } else if (pattern.startsWith("*.")) {
                return pattern.substring(2) + "$";
            }
            return pattern.replace(".", "\\/").replace("*", ".*");
        }
        return pattern.replace(".", "\\/");
    }

    /**
     * Custom exception for parser errors
     */
    public static class ExclusionParseException extends RuntimeException {
        private final int lineNumber;

        public ExclusionParseException(String message) {
            this(message, -1);
        }

        public ExclusionParseException(String message, int lineNumber) {
            super(lineNumber == -1 ? message : "Line " + lineNumber + ": " + message);
            this.lineNumber = lineNumber;
        }

        public ExclusionParseException(String message, Throwable cause) {
            super(message, cause);
            this.lineNumber = -1;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    /**
     * Main entry point for parsing exclusion patterns
     */
    public Exclusion parsePatternExclusion(String input) {
        try {
            ParsedClass parsedClass = parseClass(input);
            return generateExclusion(parsedClass);
        } catch (Exception e) {
            throw new ExclusionParseException("Failed to parse exclusion pattern: " + input, e);
        }
    }

    /**
     * Generates Exclusion with testers from parsed class
     */
    private Exclusion generateExclusion(ParsedClass parsedClass) {
        ExclusionMap map = new ExclusionMap();

        // Generate class tester
        generateClassTester(map, parsedClass);

        // Generate method testers
        generateMethodTesters(map, parsedClass);

        // Generate field testers
        generateFieldTesters(map, parsedClass);

        return Exclusion.builder().testers(map).build();
    }

    private void generateClassTester(ExclusionMap map, ParsedClass parsedClass) {
        final Pattern regex = Pattern.compile(convertClassPattern(parsedClass.getName()));
        //System.out.println("regex: " + regex.pattern());

        map.put(ExclusionType.CLASS, new ExclusionTester<ClassNode>() {
            @Override
            public boolean test(ClassNode var) {
                //System.out.println("testing: " + var.getName());
                parsedClass.getModifiers().forEach(System.out::println);
                final boolean initialMatch = Match.of("")
                        .match("static", var.isStatic())
                        .match("public", var.isPublic())
                        .match("protected", var.isProtected())
                        .match("private", var.isPrivate())
                        .match("abstract", var.isAbstract())
                        .match("final", var.isFinal())
                        .match("interface", var.isInterface() && parsedClass.getType().equals("interface"))
                        .match("annotation", var.isAnnotation() && parsedClass.getType().equals("annotation"))
                        .match("enum", var.isEnum() && parsedClass.getType().equals("enum"))
                        .match("synthetic", var.isSynthetic())
                        .match("extends", parsedClass.getExtendsClass() == null ||
                                regex.matcher(var.getSuperName()).find())
                        .match("implements", parsedClass.getInterfaces().isEmpty() ||
                                var.getInterfaces().stream()
                                        .anyMatch(iface -> parsedClass.getInterfaces().stream()
                                                .anyMatch(required -> Pattern.compile(convertClassPattern(required))
                                                        .matcher(iface).find())))
                        .check();

                if (!initialMatch) return false;

                // Check modifiers match
                for (String modifier : parsedClass.getModifiers()) {
                    switch (modifier) {
                        case "public": if (!var.isPublic()) return false; break;
                        case "private": if (!var.isPrivate()) return false; break;
                        case "protected": if (!var.isProtected()) return false; break;
                        case "static": if (!var.isStatic()) return false; break;
                        case "final": if (!var.isFinal()) return false; break;
                        case "abstract": if (!var.isAbstract()) return false; break;
                    }
                }

                boolean initialNameMatch = regex.matcher(var.getName()).find() != parsedClass.isInclude();

                for (ParsedMember member : parsedClass.getMembers()) {
                    if (!member.getType().equalsIgnoreCase("class"))
                        continue;

                    final boolean match = Pattern
                            .compile(convertClassPattern(parsedClass.getName() + member.getName()))
                            .matcher(var.getName())
                            .find();

                    if (match) {
                        initialNameMatch = match != member.isInclusion();
                    }
                }

                return initialNameMatch;
            }

            @Override
            public String toString() {
                return regex.pattern();
            }
        });
    }

    private void generateMethodTesters(ExclusionMap map, ParsedClass parsedClass) {
        final Pattern classRegex = Pattern.compile(convertClassPattern(parsedClass.getName()));
        final List<Pattern> includedClassPatterns = new ArrayList<>();
        final List<MethodPattern> methodPatterns = new ArrayList<>();

        // Collect patterns from members
        for (ParsedMember member : parsedClass.getMembers()) {
            switch (member.getType()) {
                case "class" -> {
                    if (member.isInclusion()) {
                        includedClassPatterns.add(Pattern.compile(convertClassPattern(member.getName())));
                    }
                }
                case "method" -> {
                    methodPatterns.add(new MethodPattern(
                            member.getName().replace("*", ".*"),
                            member.getModifiers(),
                            member.getReturnType(),
                            member.getParameters(),
                            member.isInclusion()
                    ));
                }
                default -> throw new ExclusionParseException("Invalid member type: " + member.getType());
            }
        }

        map.put(ExclusionType.METHOD, new ExclusionTester<MethodNode>() {
            @Override
            public boolean test(MethodNode var) {
                String ownerName = var.getOwnerClass().getName();

                // First check if the method's class matches any inclusion pattern
                for (Pattern includePattern : includedClassPatterns) {
                    if (includePattern.matcher(ownerName).find()) {
                        return false; // Include methods from included classes
                    }
                }

                // Check if the method matches any specific method patterns
                for (MethodPattern pattern : methodPatterns) {
                    if (pattern.matches(var)) {
                        return !pattern.isInclusion(); // Return false for inclusions, true for exclusions
                    }
                }

                return false;
            }

            @Override
            public String toString() {
                return "MethodTester[class=" + classRegex.pattern() + ", patterns=" + methodPatterns.size() + "]";
            }
        });
    }

    private static class MethodPattern {
        private final Pattern namePattern;
        private final Set<String> modifiers;
        private final String returnType;
        private final List<String> parameters;
        private final boolean inclusion;

        public MethodPattern(String name, Set<String> modifiers, String returnType, List<String> parameters, boolean inclusion) {
            this.namePattern = Pattern.compile(name);
            this.modifiers = modifiers;
            this.returnType = returnType;
            this.parameters = parameters;
            this.inclusion = inclusion;
        }

        public boolean matches(MethodNode method) {
            // Check name pattern
            if (!namePattern.matcher(method.getDisplayName()).matches()) {
                return false;
            }

            // Check modifiers if specified
            for (String modifier : modifiers) {
                switch (modifier) {
                    case "public": if (!method.isPublic()) return false; break;
                    case "private": if (!method.isPrivate()) return false; break;
                    case "protected": if (!method.isProtected()) return false; break;
                    case "static": if (!method.isStatic()) return false; break;
                    case "final": if (!method.isFinal()) return false; break;
                    case "abstract": if (!method.isAbstract()) return false; break;
                    case "native": if (!method.isNative()) return false; break;
                }
            }

            // Check return type if specified
            if (returnType != null) {
                if (!Pattern.compile(returnType).matcher(method.getDesc()).find()) {
                    return false;
                }
            }

            // Check parameters if specified
            if (!parameters.isEmpty()) {
                // TODO: Add parameter matching logic
                // This would require parsing the method descriptor
            }

            return true;
        }

        public boolean isInclusion() {
            return inclusion;
        }

        @Override
        public String toString() {
            return (inclusion ? "!" : "") + namePattern.pattern() + 
                   (modifiers.isEmpty() ? "" : " " + modifiers) +
                   (returnType != null ? " returns " + returnType : "") +
                   (parameters.isEmpty() ? "" : " params " + parameters);
        }
    }

    private void generateFieldTesters(ExclusionMap map, ParsedClass parsedClass) {
        List<ExclusionTester<FieldNode>> fieldTesters = new ArrayList<>();

        for (ParsedMember member : parsedClass.getMembers()) {
            if (!member.getType().equals("field")) continue;

            final Pattern nameRegex = Pattern.compile(member.getName().replace("*", ".*"));
            final Pattern typeRegex = member.getReturnType() != null ?
                    Pattern.compile(member.getReturnType()) : null;

            fieldTesters.add(new ExclusionTester<FieldNode>() {
                @Override
                public boolean test(FieldNode var) {
                    // Check basic modifiers
                    final boolean initialMatch = Match.of("")
                            .match("static", var.isStatic())
                            .match("public", var.isPublic())
                            .match("protected", var.isProtected())
                            .match("private", var.isPrivate())
                            .check();

                    if (!initialMatch) return false;

                    // Check specific modifiers
                    for (String modifier : member.getModifiers()) {
                        switch (modifier) {
                            case "public": if (!var.isPublic()) return false; break;
                            case "private": if (!var.isPrivate()) return false; break;
                            case "protected": if (!var.isProtected()) return false; break;
                            case "static": if (!var.isStatic()) return false; break;
                            case "final": if (!var.isFinal()) return false; break;
                            case "volatile": if (!var.isVolatile()) return false; break;
                            case "transient": if (!var.isTransient()) return false; break;
                        }
                    }

                    // Check field type if specified
                    if (typeRegex != null && !typeRegex.matcher(var.getDesc()).lookingAt()) {
                        return false;
                    }

                    // Check field name
                    return nameRegex.matcher(var.getDisplayName()).lookingAt();
                }

                @Override
                public String toString() {
                    return nameRegex.pattern();
                }
            });
        }

        // Combine all field testers into one
        map.put(ExclusionType.FIELD, new ExclusionTester<FieldNode>() {
            @Override
            public boolean test(FieldNode var) {
                return fieldTesters.stream().anyMatch(tester -> tester.test(var));
            }

            @Override
            public String toString() {
                return "CombinedFieldTester";
            }
        });
    }
}