import { AnalysisPage } from "@/components/research/AnalysisPage";

export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <AnalysisPage id={id} />;
}
