import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * .env ファイル (例: api_key.env) から指定されたキーの値を読み込み、
 * システムプロパティまたは環境変数 (System.setProperty) として設定するユーティリティクラス。
 * * 注意: Javaの System.getenv() は実行中のプロセス環境変数を読み込むため、
 * System.setProperty() で設定しても System.getenv() からは直接アクセスできません。
 * * しかし、一般的にAPIキーは System.getenv() または System.getProperty() で読み込まれるため、
 * ここでは System.setProperty() を使用し、プログラム内で System.getProperty() でアクセス可能にします。
 * * プログラムの他の部分で System.getenv() を使いたい場合は、
 * プログラム実行前にOSの環境変数として設定する必要があります。
 */
public class DotEnvLoader {

    /**
     * 指定されたファイルパスから環境変数キーの値を読み込み、システムプロパティとして設定します。
     * @param filePath 読み込む設定ファイルのパス (例: "api_key.env")
     * @param envVarName 設定ファイル内の環境変数名 (例: "OPENAI_API_KEY")
     * @throws IOException ファイルの読み込みエラーが発生した場合
     * @throws IllegalStateException 指定されたキーがファイルに見つからない場合
     */
    public static void load(String filePath, String envVarName) throws IOException, IllegalStateException {
        Properties properties = new Properties();

        try (FileInputStream fis = new FileInputStream(filePath)) {
            properties.load(fis);
            
            String value = properties.getProperty(envVarName);

            if (value == null || value.trim().isEmpty()) {
                throw new IllegalStateException(
                    "エラー: 設定ファイル '" + filePath + "' 内にキー '" + envVarName + "' が見つからないか、値が空です。"
                );
            }

            // 読み込んだ値をシステムプロパティとして設定
            // これにより、プログラムの他の部分で System.getProperty(envVarName) でアクセス可能になる
            System.setProperty(envVarName, value.trim());
            
            System.out.println("成功: ファイル '" + filePath + "' から '" + envVarName + "' をロードし、システムプロパティに設定しました。");

        } catch (IOException e) {
            // ファイルが存在しない、または読み込みエラーの場合
            throw new IOException("エラー: 設定ファイル '" + filePath + "' の読み込みに失敗しました。ファイルが存在するか確認してください。", e);
        }
    }
    
    /**
     * テスト用のメインメソッド。
     * 実際に読み込みを行う場合は、api_key.env ファイルを用意し、その中に OPENAI_API_KEY=YOUR_KEY のように記述してください。
     */
    public static void main(String[] args) {
        final String TEST_FILE = "api_key.env";
        final String TEST_KEY = "OPENAI_API_KEY";

        try {
            // 読み込みの実行
            // 💡 実際のプログラムでは、この処理をアプリケーション起動時に一度だけ実行します。
            DotEnvLoader.load(TEST_FILE, TEST_KEY);

            // 読み込まれた値の検証 (System.getPropertyでアクセス)
            String loadedKey = System.getProperty(TEST_KEY);
            System.out.println("\n--- 検証 ---");
            if (loadedKey != null) {
                // セキュリティのため、キーの一部だけを表示
                String maskedKey = loadedKey.length() > 8 ? 
                                   loadedKey.substring(0, 4) + "..." + loadedKey.substring(loadedKey.length() - 4) : 
                                   "****";
                System.out.println("システムプロパティ '" + TEST_KEY + "' の値: " + maskedKey);

                // 補足: System.getenv() は OSの環境変数を読み込むため、System.setProperty() で設定した値は読み込めません
                String envKey = System.getenv(TEST_KEY);
                System.out.println("OS環境変数 'OPENAI_API_KEY' の値: " + (envKey != null ? "設定済み" : "未設定"));

            } else {
                System.out.println("システムプロパティは設定されませんでした。");
            }
        } catch (Exception e) {
            System.err.println("\nメイン処理で例外が発生しました: " + e.getMessage());
        }
    }
}