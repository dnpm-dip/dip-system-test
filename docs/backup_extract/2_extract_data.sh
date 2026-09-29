# Piped through cat: the snap-installed docker may not write into a redirected file ("write /dev/stdout: bad file descriptor")
docker compose exec -T ccdn-mongodb mongosh --quiet ccdn --eval 'EJSON.stringify(db.backup.find({}, {_id: 0}).toArray())' | cat > backup.json
