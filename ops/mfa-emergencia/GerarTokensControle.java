import com.nekkora.core.token.Tokens;

/**
 * Controle positivo da varredura (ops/mfa-emergencia/validar.sh): imprime N tokens gerados pela MESMA classe de
 * producao que a Platform usa para os tokens de aviso (32 bytes aleatorios em base64url). Os tokens so existem no
 * diretorio temporario do runner e nunca vao para a evidencia.
 * Uso: java -cp nekkora-core.jar GerarTokensControle.java N
 */
public class GerarTokensControle {
    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]);
        for (int i = 0; i < n; i++) {
            System.out.println(Tokens.newToken());
        }
    }
}
