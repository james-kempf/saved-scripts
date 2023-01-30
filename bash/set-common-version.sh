cd common-pace-ux-api
#mvn clean install -DskipTests
VERSION=$(xpath -q -e '/project/version/text()' pom.xml)
echo $VERSION
cd ..

for repo in ./*/; do
  cd ${repo}
  pwd
  xmlstarlet edit -P --inplace --update '//_:dependency[_:artifactId="common-pace-ux-api"]/_:version' --value $VERSION pom.xml
#  mvn clean package -DskipTests
  cd ..
done
