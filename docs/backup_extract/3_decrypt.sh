mkdir -p decrypted
  jq -c '.[]' backup.json | while read -r doc; do
    name="$(jq -r '.tan[0:12] + "_" + .type' <<<"$doc")"
    jq -r .content.encryptedKey <<<"$doc" | base64 -d \
      | openssl pkeyutl -decrypt -inkey crypto/private.pem -passin file:crypto/private_passphrase.txt \
          -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 -pkeyopt rsa_mgf1_md:sha256 \
          -out aes_key.bin \
    && jq -r .content.ciphertext <<<"$doc" | base64 -d \
      | openssl enc -d -aes-256-cbc -K "$(xxd -p -c 256 aes_key.bin)" \
          -iv "$(jq -r .content.iv <<<"$doc" | base64 -d | xxd -p -c 32)" \
      | jq . > "decrypted/$name.json" \
    || echo "FAILED: $name"
  done
  rm -f aes_key.bin