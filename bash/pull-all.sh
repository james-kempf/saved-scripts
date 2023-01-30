for repo in ./*/; do
  cd ${repo}
  pwd
  git stash
  git checkout develop && git pull
#  git stash pop
  cd ..
done
