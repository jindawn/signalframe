import { TopicPage } from "@/components/research/TopicPage";
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <TopicPage id={id} />;
}
