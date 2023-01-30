for repo in ./*/; do
  echo repo: ${repo}
  cd ${repo}
  current_branch=$(git branch --show-current)
  echo current: ${current_branch}
  for branch in $(git branch | grep -v HEAD | grep -v develop | grep -v main | grep -v master | grep -v ${current_branch} | sed /\*/d); do
    echo ${branch}
    remote_branch=$(echo ${branch})
    git branch -D ${branch}
  done
  echo DONE
  cd ..
done
