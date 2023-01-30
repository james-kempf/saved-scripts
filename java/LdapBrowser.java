import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

public class LdapBrowser {

    private static final Deque<String> path = new ArrayDeque<>();
    private static LdapContext ldapContext;

    public static void main(String[] args) throws IOException {
        connectLdap(args[0], args[1], args[2]);
        String[] root = args[3].split(",");
        for (int i = root.length - 1; i >= 0; i--) {
            path.push(root[i]);
        }
        String command = "";
        while (!command.equalsIgnoreCase("exit")) {
            System.out.print("ldap-browser " + ldapPath() + " $ ");
            // Enter data using BufferReader
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in));

            // Reading data using readLine
            command = reader.readLine();

            if (command.matches("cd .*")) {
                cdCommand(command);
            } else if (command.matches("ls.*")) {
                lsCommand(command);
            } else if (command.matches("pwd")) {
                pwdCommand();
            } else if (command.matches("cat .+")) {
                catCommand(command);
            }
        }
    }

    private static void lsCommand(String command) {
        try {
            String[] commandParts = command.split(" ");
            String param = commandParts.length >= 2 ? commandParts[1] : "(|(OU=*)(CN=*))";
            String ldapPath = ldapPath();
            NamingEnumeration<SearchResult> namingEnum = ldapContext.search(
                    ldapPath,
                    param,
                    getSimpleSearchControls());
            int i = 0;
            List<String> results = new ArrayList<>();
            boolean capped = false;
            try {
                while (namingEnum.hasMore()) {
                    SearchResult result = namingEnum.next();
                    Attributes attributes = result.getAttributes();
                    String distinguishedName = Objects.toString(attributes.get("distinguishedName"))
                            .replace("distinguishedName: ", "");
                    if (distinguishedName.matches("..=\\w+," + ldapPath)) {
                        i++;
                        String fields = "";
                        if (attributes.get(param) != null) {
                            fields += attributes.get(param);
                        }
                        fields += distinguishedName;
                        if (!"".equalsIgnoreCase(fields)) {
                            results.add(fields);
                        }
                    }
                }
            } catch (SizeLimitExceededException e) {
                capped = true;
            }
            System.out.println(String.join("\n", results));
            if (capped) {
                System.out.println("results capped at " + results.size());
            }
            System.out.println("total = " + i);
            namingEnum.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void cdCommand(String command) {
        String param = command.split(" ")[1];
        if (param.equalsIgnoreCase("..")) {
            if (!path.isEmpty()) {
                path.pop();
            }
        } else {
            path.push(command.split(" ")[1]);
        }
        System.out.println(String.join(",", path));
    }

    private static void pwdCommand() {
        System.out.println(ldapPath());
    }

    private static void catCommand(String command) {
        try {
            String[] commandParts = command.split(" ");
            String param = commandParts[1];
            NamingEnumeration<SearchResult> namingEnum = ldapContext.search(
                    param,
                    "distinguishedName=*",
                    getSimpleSearchControls());
            List<String> results = new ArrayList<>();
            try {
                while (namingEnum.hasMore()) {
                    SearchResult result = namingEnum.next();
                    Attributes attributes = result.getAttributes();
                    NamingEnumeration<? extends Attribute> ne = attributes.getAll();
                    while (ne.hasMore()) {
                        results.add(Objects.toString(ne.next()));
                    }
                }
            } catch (SizeLimitExceededException e) {
                e.printStackTrace();
            }
            System.out.println(String.join("\n", results));
            namingEnum.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String ldapPath() {
        return String.join(",", path);
    }

    private static void connectLdap(String url, String user, String password) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, url);
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, user);
            env.put(Context.SECURITY_CREDENTIALS, password);
            env.put(Context.BATCHSIZE, Integer.toString(1000000));

            ldapContext = new InitialLdapContext(env, null);
            ldapContext.setRequestControls(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static SearchControls getSimpleSearchControls() {
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        searchControls.setTimeLimit(30000);
        return searchControls;
    }
}
